#include "translation.hpp"
#include "llama.h"
#include "onnxruntime_cxx_api.h"
#include "sentencepiece_processor.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <fstream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <vector>

using json = nlohmann::json;
void TranslationEngine::checkCancelled() const {
    if (cancelled.load()) throw std::runtime_error("Translation cancelled");
}
namespace {
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
class HyMt final : public TranslationEngine {
    Model model{nullptr, llama_model_free};
    Context context{nullptr, llama_free};
public:
    HyMt(const std::string & path, int threads) {
        static std::once_flag once;
        std::call_once(once, [] { llama_backend_init(); });
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        model.reset(llama_model_load_from_file(path.c_str(), params));
        if (!model) throw std::runtime_error("Hy-MT2 model load failed");
        auto options = llama_context_default_params();
        options.n_ctx = 2048; options.n_batch = 256; options.n_ubatch = 128;
        options.n_threads = threads; options.n_threads_batch = threads;
        options.abort_callback = [](void * value) { return static_cast<HyMt *>(value)->cancelled.load(); };
        options.abort_callback_data = this;
        context.reset(llama_init_from_model(model.get(), options));
        if (!context) throw std::runtime_error("Hy-MT2 context creation failed");
    }
    std::string translate(const std::string & prompt) override {
        checkCancelled();
        llama_memory_clear(llama_get_memory(context.get()), true);
        const auto * vocab = llama_model_get_vocab(model.get());
        const auto * format = llama_model_chat_template(model.get(), nullptr);
        if (!format) throw std::runtime_error("GGUF is missing its chat template");
        llama_chat_message message{"user", prompt.c_str()};
        int length = llama_chat_apply_template(format, &message, 1, true, nullptr, 0);
        if (length <= 0) throw std::runtime_error("Unsupported Hy-MT2 chat template");
        std::vector<char> chat(length + 1);
        int written = llama_chat_apply_template(format, &message, 1, true, chat.data(), chat.size());
        if (written != length) throw std::runtime_error("Chat template length mismatch");
        int count = -llama_tokenize(vocab, chat.data(), length, nullptr, 0, true, true);
        constexpr int max_output = 384;
        if (count <= 0 || count + max_output > 2048) throw std::runtime_error("Translation input exceeds context limit");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, chat.data(), length, tokens.data(), count, true, true) != count)
            throw std::runtime_error("Hy-MT2 tokenization failed");
        for (int offset = 0; offset < count; offset += 256) {
            checkCancelled();
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset));
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Hy-MT2 prompt decode failed or cancelled");
        }
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0, 0));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        std::string result;
        for (int i = 0; i < max_output; ++i) {
            checkCancelled();
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) return result;
            char buffer[256];
            int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            if (size < 0) {
                std::vector<char> larger(-size);
                size = llama_token_to_piece(vocab, token, larger.data(), larger.size(), 0, false);
                if (size < 0) throw std::runtime_error("Invalid translation token");
                result.append(larger.data(), size);
            } else result.append(buffer, size);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Hy-MT2 decode failed or cancelled");
        }
        throw std::runtime_error("Hy-MT2 output exceeded token limit; not committing truncated translation");
    }
};

json read_json(const std::string & path) {
    std::ifstream in(path);
    if (!in) throw std::runtime_error("Missing model metadata: " + path);
    return json::parse(in);
}
Ort::Env & environment() { static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "live-captions"); return env; }
class Opus final : public TranslationEngine {
    Ort::Session encoder{nullptr}, decoder{nullptr};
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    sentencepiece::SentencePieceProcessor source, target;
    json vocab;
    std::vector<std::string> pieces, in_names, out_names;
    int layers, heads, head_dim, start_id, eos_id, pad_id, unk_id;
public:
    Opus(const std::string & dir, int threads) {
        auto config = read_json(dir + "/config.json");
        vocab = read_json(dir + "/vocab.json");
        layers = config.at("decoder_layers"); heads = config.at("decoder_attention_heads");
        head_dim = config.at("d_model").get<int>() / heads;
        start_id = config.at("decoder_start_token_id"); eos_id = config.at("eos_token_id"); pad_id = config.at("pad_token_id"); unk_id = vocab.at("<unk>");
        pieces.resize(config.at("vocab_size").get<size_t>());
        for (const auto & entry : vocab.items()) {
            size_t id = entry.value().get<size_t>();
            if (id >= pieces.size()) throw std::runtime_error("OPUS vocabulary ID out of range");
            pieces[id] = entry.key();
        }
        if (!source.Load(dir + "/source.spm").ok() || !target.Load(dir + "/target.spm").ok())
            throw std::runtime_error("SentencePiece model load failed");
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads); options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        encoder = Ort::Session(environment(), (dir + "/encoder.onnx").c_str(), options);
        decoder = Ort::Session(environment(), (dir + "/decoder.onnx").c_str(), options);
        in_names = {"encoder_attention_mask", "input_ids", "encoder_hidden_states"};
        out_names = {"logits"};
        for (int i = 0; i < layers; ++i) for (const auto * attention : {"decoder", "encoder"}) for (const auto * kind : {"key", "value"}) {
            auto suffix = std::to_string(i) + "." + attention + "." + kind;
            in_names.push_back("past_key_values." + suffix); out_names.push_back("present." + suffix);
        }
        in_names.push_back("use_cache_branch");
        if (decoder.GetInputCount() != in_names.size() || decoder.GetOutputCount() != out_names.size())
            throw std::runtime_error("Unsupported OPUS decoder signature");
        Ort::AllocatorWithDefaultOptions allocator;
        for (size_t i = 0; i < in_names.size(); ++i)
            if (in_names[i] != decoder.GetInputNameAllocated(i, allocator).get()) throw std::runtime_error("Unexpected decoder input name");
        for (size_t i = 0; i < out_names.size(); ++i)
            if (out_names[i] != decoder.GetOutputNameAllocated(i, allocator).get()) throw std::runtime_error("Unexpected decoder output name");
    }
    std::string translate(const std::string & text) override {
        checkCancelled();
        std::vector<std::string> input_pieces;
        if (!source.Encode(text, &input_pieces).ok()) throw std::runtime_error("SentencePiece encoding failed");
        if (input_pieces.size() > 383) throw std::runtime_error("OPUS input too long");
        std::vector<int64_t> ids;
        for (const auto & piece : input_pieces) ids.push_back(vocab.value(piece, unk_id));
        ids.push_back(eos_id);
        std::vector<int64_t> mask(ids.size(), 1);
        const int64_t shape[] = {1, static_cast<int64_t>(ids.size())};
        std::vector<Ort::Value> input;
        input.push_back(Ort::Value::CreateTensor<int64_t>(memory, ids.data(), ids.size(), shape, 2));
        input.push_back(Ort::Value::CreateTensor<int64_t>(memory, mask.data(), mask.size(), shape, 2));
        const char * enc_in[] = {"input_ids", "attention_mask"}; const char * enc_out[] = {"last_hidden_state"};
        Ort::RunOptions run;
        auto hidden = encoder.Run(run, enc_in, input.data(), input.size(), enc_out, 1);
        std::vector<Ort::Value> feed;
        int64_t token = start_id; const int64_t one[] = {1, 1}; const int64_t cache_shape[] = {1, heads, 0, head_dim};
        bool cached = false; const int64_t bool_shape[] = {1};
        feed.push_back(Ort::Value::CreateTensor<int64_t>(memory, mask.data(), mask.size(), shape, 2));
        feed.push_back(Ort::Value::CreateTensor<int64_t>(memory, &token, 1, one, 2));
        feed.push_back(std::move(hidden[0]));
        Ort::AllocatorWithDefaultOptions allocator;
        for (int i = 0; i < layers * 4; ++i) feed.push_back(Ort::Value::CreateTensor<float>(allocator, cache_shape, 4));
        feed.push_back(Ort::Value::CreateTensor<bool>(memory, &cached, 1, bool_shape, 1));
        std::vector<const char *> inputs, outputs;
        for (auto & s : in_names) inputs.push_back(s.c_str());
        for (auto & s : out_names) outputs.push_back(s.c_str());
        std::vector<std::string> translated;
        for (int step = 0; step < 256; ++step) {
            checkCancelled();
            auto result = decoder.Run(run, inputs.data(), feed.data(), feed.size(), outputs.data(), outputs.size());
            auto * logits = result[0].GetTensorMutableData<float>();
            if (result[0].GetTensorTypeAndShapeInfo().GetElementCount() != pieces.size())
                throw std::runtime_error("Unexpected OPUS logits shape");
            logits[pad_id] = -std::numeric_limits<float>::infinity();
            token = std::max_element(logits, logits + pieces.size()) - logits;
            if (token == eos_id) {
                std::string decoded;
                if (!target.Decode(translated, &decoded).ok()) throw std::runtime_error("SentencePiece decoding failed");
                return decoded;
            }
            translated.push_back(pieces.at(token));
            for (int i = 0; i < layers * 4; ++i) {
                // The cached branch returns empty cross-attention tensors: preserve first-step encoder KV.
                if (step > 0 && i % 4 >= 2) continue;
                feed[3 + i] = std::move(result[1 + i]);
            }
            cached = true;
        }
        throw std::runtime_error("OPUS output exceeded token limit");
    }
};
}
std::unique_ptr<TranslationEngine> load_hymt(const std::string & path, int threads) { return std::make_unique<HyMt>(path, threads); }
std::unique_ptr<TranslationEngine> load_opus(const std::string & path, int threads) { return std::make_unique<Opus>(path, threads); }
