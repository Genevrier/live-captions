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
        if (argc != 6) throw std::runtime_error(
            "Usage: translation-benchmark <hy|opus> <model.gguf|opus-dir> <threads> <cases.jsonl> <results.jsonl>");
        const std::string mode = argv[1];
        if (mode != "hy" && mode != "opus") throw std::runtime_error("Mode must be hy or opus");
        const int threads = std::stoi(argv[3]);
        std::ifstream input(argv[4]);
        std::ofstream output(argv[5]);
        if (!input || !output) throw std::runtime_error("Could not open benchmark input/output");

        const auto loadStart = Clock::now();
        auto engine = mode == "hy"
            ? load_hymt(argv[2], threads, 256, 128, false, "")
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
                  << " backend=\"" << engine->backend() << "\"\n";

        std::string line;
        while (std::getline(input, line)) {
            if (line.empty()) continue;
            const auto sample = json::parse(line);
            const std::string source = sample.at("source").get<std::string>();
            const std::string prompt = mode == "hy"
                ? "Translate the following text into English. Only output the translated result without any additional explanation:\n\n" + source
                : source;
            const auto start = Clock::now();
            const auto translated = engine->translate(prompt);
            const auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
            if (translated.empty()) throw std::runtime_error("Empty translation for " + sample.at("id").get<std::string>());
            const auto stats = engine->stats();
            json result = {
                {"id", sample.at("id")}, {"source", source},
                {"reference_en", sample.at("reference_en")}, {"translation", translated},
                {"elapsed_us", elapsedUs}, {"prefill_ms", stats.prefill_ms},
                {"decode_ms", stats.decode_ms}, {"first_token_ms", stats.first_token_ms},
                {"output_tokens", stats.output_tokens}, {"process_max_rss_kb", maxRssKb()},
                {"backend", engine->backend()}
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
