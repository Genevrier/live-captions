#include "translation.hpp"
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <vector>
int main(int argc, char ** argv) {
    try {
        if (argc != 3) throw std::runtime_error("Usage: translation-smoke hymt.gguf opus-directory");
        auto hymt = load_hymt(argv[1], 4);
        std::vector<std::pair<std::string, std::string>> cases = {
            {"Translate the following text into English. Only output the translated result without any additional explanation:\n\nGoedemorgen. De vergadering begint om negen uur.", "meeting"},
            {"Reference the following translations:\n晶圆 translates to wafer\n套刻 translates to overlay\n\nTranslate the following text into English. Only output the translated result without any additional explanation:\n\n请检查晶圆上的套刻误差。", "wafer"},
            {"Translate the following text into French. Only output the translated result without any additional explanation:\n\nPlease check the temperature before starting the machine.", "température"}
        };
        for (const auto & test : cases) {
            auto start = std::chrono::steady_clock::now();
            auto result = hymt->translate(test.first);
            if (result.find(test.second) == std::string::npos) throw std::runtime_error("Hy-MT2 smoke mismatch: " + result);
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            std::cout << "Hy-MT2 CPU: " << result << " (" << ms << " ms)" << std::endl;
        }
        auto opus = load_opus(argv[2], 2);
        for (int n = 0; n < 2; ++n) {
            auto result = opus->translate("De temperatuur van de machine is te hoog.");
            if (result.find("temperature") == std::string::npos || result.find("too high") == std::string::npos)
                throw std::runtime_error("OPUS smoke mismatch: " + result);
            std::cout << "OPUS cached decoder CPU: " << result << std::endl;
        }
        for (auto * engine : {hymt.get(), opus.get()}) {
            engine->cancel(); bool rejected = false;
            try { engine->translate("Must not execute after cancellation"); } catch (const std::runtime_error &) { rejected = true; }
            if (!rejected) throw std::runtime_error("Cancellation was ignored");
        }
        std::cout << "PASS: three translation directions, decoder caching, context reset, cancellation" << std::endl;
        return 0;
    } catch (const std::exception & e) { std::cerr << e.what() << std::endl; return 1; }
}
