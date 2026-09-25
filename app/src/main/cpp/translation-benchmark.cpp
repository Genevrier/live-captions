#include "translation.hpp"
#include "nlohmann/json.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <sys/resource.h>

using json = nlohmann::json;
using Clock = std::chrono::steady_clock;

static long maxRssKb() {
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) return -1;
    return usage.ru_maxrss;
}

int main(int argc, char ** argv) {
    try {
        if (argc < 6 || argc > 10) throw std::runtime_error(
            "Usage: translation-benchmark <hy|opus> <model.gguf|opus-dir> <threads> <cases.jsonl> <results.jsonl> [cpu|opencl] [batch] [ubatch] [cache-ab]");
        const std::string mode = argv[1];
        if (mode != "hy" && mode != "opus") throw std::runtime_error("Mode must be hy or opus");
        const int threads = std::stoi(argv[3]);
        const std::string requestedBackend = argc >= 7 ? argv[6] : "cpu";
        if (requestedBackend != "cpu" && requestedBackend != "opencl")
            throw std::runtime_error("Backend must be cpu or opencl");
        const int batch = argc >= 8 ? std::stoi(argv[7]) : 256;
        const int ubatch = argc >= 9 ? std::stoi(argv[8]) : 128;
        const bool cacheAb = argc >= 10 && std::string(argv[9]) == "cache-ab";
        std::ifstream input(argv[4]);
        std::ofstream output(argv[5]);
        if (!input || !output) throw std::runtime_error("Could not open benchmark input/output");
        if (mode == "opus" && requestedBackend == "opencl") throw std::runtime_error("OPUS has no OpenCL backend");

        const auto loadStart = Clock::now();
        auto engine = mode == "hy"
            ? load_hymt(argv[2], threads, batch, ubatch, requestedBackend == "opencl", "")
            : load_opus(argv[2], threads);
        const auto loadUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - loadStart).count();

        const auto warmStart = Clock::now();
        const std::string warmText = mode == "hy"
            ? "Translate the following text into English. Only output the translated result without any additional explanation:\n\nDe temperatuur is aangenaam vandaag."
            : "De temperatuur is aangenaam vandaag.";
        const auto warmOutput = engine->translate(warmText);
        const auto warmUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - warmStart).count();
        if (warmOutput.empty()) throw std::runtime_error("Warm-up translation was empty");
        std::cerr << "model_load_us=" << loadUs << " warm_translation_us=" << warmUs
                  << " requested_backend=" << requestedBackend << " batch=" << batch << " ubatch=" << ubatch
                  << " backend=\"" << engine->backend() << "\"\n";

        std::string line;
        int64_t requestSequence = 0;
        while (std::getline(input, line)) {
            if (line.empty()) continue;
            const auto sample = json::parse(line);
            const std::string source = sample.at("source").get<std::string>();
            const std::string prompt = mode == "hy"
                ? "Translate the following text into English. Only output the translated result without any additional explanation:\n\n" + source
                : source;
            const TranslationRequestMetadata request{1, ++requestSequence, 0, "hy-chat-prompt-v1|benchmark"};
            const TranslationProgressCallback observeFirstVisible = [](const TranslationProgress &) {};
            const auto start = Clock::now();
            std::string translated;
            int64_t coldElapsedUs = -1, warmElapsedUs = -1;
            TranslationStats coldStats{}, warmStats{};
            std::string coldTranslation;
            if (cacheAb && mode == "hy") {
                const auto coldStart = Clock::now();
                coldTranslation = engine->translateUncachedWithProgress(prompt, request, observeFirstVisible);
                coldElapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - coldStart).count();
                coldStats = engine->stats();
                // Seed a completed cached request, then measure the true same-prompt warm path.
                const auto seed = engine->translateWithProgress(prompt, request, observeFirstVisible);
                if (seed != coldTranslation) throw std::runtime_error("Cached seed differs from uncached output for " + sample.at("id").get<std::string>());
                const auto warmStart = Clock::now();
                translated = engine->translateWithProgress(prompt, request, observeFirstVisible);
                warmElapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - warmStart).count();
                warmStats = engine->stats();
                if (translated != coldTranslation) throw std::runtime_error("Warm cached output differs from uncached output for " + sample.at("id").get<std::string>());
            } else {
                translated = engine->translateWithProgress(prompt, request, observeFirstVisible);
            }
            const auto elapsedUs = cacheAb && mode == "hy" ? warmElapsedUs :
                std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
            if (translated.empty()) throw std::runtime_error("Empty translation for " + sample.at("id").get<std::string>());
            const auto stats = engine->stats();
            json result = {
                {"id", sample.at("id")}, {"source", source},
                {"reference_en", sample.at("reference_en")}, {"translation", translated},
                {"elapsed_us", elapsedUs}, {"prefill_ms", stats.prefill_ms},
                {"decode_ms", stats.decode_ms}, {"first_token_ms", stats.first_token_ms},
                {"first_visible_ms", stats.first_visible_ms}, {"complete_ms", stats.complete_ms},
                {"output_tokens", stats.output_tokens}, {"process_max_rss_kb", maxRssKb()},
                {"input_tokens", stats.input_tokens}, {"cache_reused_tokens", stats.cache_reused_tokens},
                {"prefill_decode_us", stats.prefill_decode_us}, {"prefill_sync_us", stats.prefill_sync_us},
                {"sampling_us", stats.sampling_us}, {"decode_compute_us", stats.decode_compute_us},
                {"decode_sync_us", stats.decode_sync_us},
                {"device_bytes_allocated", stats.device_bytes_allocated},
                {"offloaded_layers", stats.offloaded_layers}, {"total_layers", stats.total_layers},
                {"fallback_count", stats.fallback_count}, {"backend", engine->backend()},
                {"requested_backend", requestedBackend}, {"batch", batch}, {"ubatch", ubatch},
                {"cache_ab", cacheAb && mode == "hy"}, {"cold_elapsed_us", coldElapsedUs},
                {"cold_prefill_ms", coldStats.prefill_ms}, {"cold_decode_ms", coldStats.decode_ms},
                {"cold_first_visible_ms", coldStats.first_visible_ms}, {"cold_complete_ms", coldStats.complete_ms},
                {"cold_input_tokens", coldStats.input_tokens}, {"cold_output_tokens", coldStats.output_tokens},
                {"warm_elapsed_us", warmElapsedUs}, {"warm_first_visible_ms", warmStats.first_visible_ms},
                {"warm_complete_ms", warmStats.complete_ms}, {"warm_cache_reused_tokens", warmStats.cache_reused_tokens}
            };
            output << result.dump() << '\n';
            if (!output) throw std::runtime_error("Could not write benchmark result");
        }
        engine->cancel();
        std::cout << "PASS: resident " << mode << " model benchmark completed\n";
        return 0;
    } catch (const std::exception & error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
