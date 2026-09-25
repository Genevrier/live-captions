#include "translation.hpp"
#include <algorithm>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

static bool validUtf8(const std::string & value) {
    size_t offset = 0;
    while (offset < value.size()) {
        const auto lead = static_cast<unsigned char>(value[offset]);
        size_t width = 0;
        if (lead <= 0x7f) width = 1;
        else if (lead >= 0xc2 && lead <= 0xdf) width = 2;
        else if (lead >= 0xe0 && lead <= 0xef) width = 3;
        else if (lead >= 0xf0 && lead <= 0xf4) width = 4;
        else return false;
        if (offset + width > value.size()) return false;
        for (size_t i = 1; i < width; ++i) {
            const auto continuation = static_cast<unsigned char>(value[offset + i]);
            if ((continuation & 0xc0) != 0x80) return false;
            if (i == 1 && ((lead == 0xe0 && continuation < 0xa0) ||
                           (lead == 0xed && continuation >= 0xa0) ||
                           (lead == 0xf0 && continuation < 0x90) ||
                           (lead == 0xf4 && continuation >= 0x90))) return false;
        }
        offset += width;
    }
    return true;
}

int main(int argc, char ** argv) {
    try {
        if (argc != 4) throw std::runtime_error("Usage: translation-smoke hymt.gguf opus-nl-directory opus-fr-directory");
        auto hymt = load_hymt(argv[1], 4, 256, 128, false, "");
        std::vector<std::pair<std::string, std::string>> cases = {
            {"Translate the following text into English. Only output the translated result without any additional explanation:\n\nGoedemorgen. De vergadering begint om negen uur.", "meeting"},
            {"Reference the following translations:\n晶圆 translates to wafer\n套刻 translates to overlay\n\nTranslate the following text into English. Only output the translated result without any additional explanation:\n\n请检查晶圆上的套刻误差。", "wafer"},
            {"Translate the following text into French. Only output the translated result without any additional explanation:\n\nPlease check the temperature before starting the machine.", "température"}
        };
        TranslationRequestMetadata request{41, 3, 2, "hy-chat-v1|target=en|glossary=none"};
        std::vector<TranslationProgress> updates;
        auto first = hymt->translateWithProgress(cases[0].first, request, [&](const TranslationProgress & progress) {
            if (progress.session_id != request.session_id || progress.segment_id != request.segment_id ||
                progress.revision != request.revision || !progress.provisional || !validUtf8(progress.text))
                throw std::runtime_error("Invalid native progressive translation metadata or UTF-8");
            if (!updates.empty() && progress.elapsed_ms - updates.back().elapsed_ms < 80)
                throw std::runtime_error("Native progress exceeded the 80 ms UI cadence");
            updates.push_back(progress);
        });
        if (first.find(cases[0].second) == std::string::npos || updates.empty())
            throw std::runtime_error("Hy-MT2 progressive smoke mismatch or missing callback");
        const auto firstStats = hymt->stats();
        if (firstStats.input_tokens <= 0 || firstStats.output_tokens <= 0 ||
            firstStats.first_visible_ms <= 0 || firstStats.complete_ms < firstStats.first_visible_ms)
            throw std::runtime_error("Hy-MT2 token or first-visible/completion timings are missing");

        auto repeated = hymt->translateWithProgress(cases[0].first, request, {});
        const auto repeatedStats = hymt->stats();
        if (repeated != first || repeatedStats.cache_reused_tokens != repeatedStats.input_tokens - 1)
            throw std::runtime_error("Exact prompt did not reuse its longest safe KV prefix");
        const auto uncachedFirst = hymt->translateUncached(cases[0].first);
        if (uncachedFirst != repeated) throw std::runtime_error("Cached output differs from full prefill output");
        const auto uncachedFirstStats = hymt->stats();

        // A changed suffix must retain the common token prefix and discard generated/divergent KV.
        const std::string changedPrompt = "Translate the following text into English. Only output the translated result without any additional explanation:\n\nGoedemorgen. De trein vertrekt om elf uur.";
        const auto reseeded = hymt->translateWithProgress(cases[0].first, request, {});
        (void) reseeded;
        const auto cachedChanged = hymt->translateWithProgress(changedPrompt, request, {});
        const auto changedStats = hymt->stats();
        if (changedStats.cache_reused_tokens <= 0)
            throw std::runtime_error("Changed prompt did not reuse its longest shared token prefix");
        const auto uncachedChanged = hymt->translateUncached(changedPrompt);
        if (cachedChanged != uncachedChanged) throw std::runtime_error("Divergent KV state contaminated another request");

        // Session and prompt-scope changes must cold-start even when prompt tokens match.
        request.session_id++;
        hymt->translateWithProgress(changedPrompt, request, {});
        if (hymt->stats().cache_reused_tokens != 0) throw std::runtime_error("Session change reused old KV state");
        hymt->translateWithProgress(changedPrompt, request, {});
        request.cache_scope = "hy-chat-v1|target=fr|glossary=updated";
        hymt->translateWithProgress(changedPrompt, request, {});
        if (hymt->stats().cache_reused_tokens != 0) throw std::runtime_error("Prompt configuration change reused old KV state");
        std::cout << "Hy-MT2 KV/progressive: input=" << firstStats.input_tokens
                  << " output=" << firstStats.output_tokens << " firstVisible=" << firstStats.first_visible_ms
                  << " ms complete=" << firstStats.complete_ms << " ms callbacks=" << updates.size()
                  << " samePromptReused=" << repeatedStats.cache_reused_tokens << "/"
                  << (repeatedStats.input_tokens - 1) << " tokens coldPrefill=" << uncachedFirstStats.prefill_ms
                  << " ms warmPrefill=" << repeatedStats.prefill_ms << " ms divergentReused="
                  << changedStats.cache_reused_tokens << " tokens; sessions/configuration invalidated" << std::endl;

        for (size_t index = 1; index < cases.size(); ++index) {
            const auto & test = cases[index];
            auto start = std::chrono::steady_clock::now();
            std::vector<TranslationProgress> unicodeUpdates;
            request.session_id++;
            request.segment_id = static_cast<int64_t>(index + 3);
            request.revision++;
            request.cache_scope = index == 2 ? "hy-chat-v1|target=fr|glossary=none" :
                "hy-chat-v1|target=en|glossary=wafer-overlay";
            auto result = hymt->translateWithProgress(test.first, request,
                index == 2 ? TranslationProgressCallback([&](const TranslationProgress & progress) {
                    if (!validUtf8(progress.text) || !progress.provisional ||
                        progress.session_id != request.session_id || progress.segment_id != request.segment_id ||
                        progress.revision != request.revision)
                        throw std::runtime_error("Invalid UTF-8 progress event on accented output");
                    unicodeUpdates.push_back(progress);
                }) : TranslationProgressCallback{});
            if (result.find(test.second) == std::string::npos) throw std::runtime_error("Hy-MT2 smoke mismatch: " + result);
            if (index == 2 && (unicodeUpdates.empty() || !std::any_of(result.begin(), result.end(),
                    [](unsigned char ch) { return ch >= 0x80; })))
                throw std::runtime_error("Accented translation did not produce a valid UTF-8 progress update");
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            const auto timing = hymt->stats();
            std::cout << "Hy-MT2 " << hymt->backend() << ": " << result << " (" << ms
                      << " ms; prefill " << timing.prefill_ms << " ms, decode " << timing.decode_ms
                      << " ms, input/output " << timing.input_tokens << "/" << timing.output_tokens
                      << " tokens, KV reused " << timing.cache_reused_tokens << ")" << std::endl;
        }
        auto opus = load_opus(argv[2], 2);
        for (int n = 0; n < 2; ++n) {
            auto result = opus->translate("De temperatuur van de machine is te hoog.");
            if (result.find("temperature") == std::string::npos || result.find("too high") == std::string::npos)
                throw std::runtime_error("OPUS smoke mismatch: " + result);
            std::cout << "OPUS cached decoder CPU: " << result << std::endl;
        }
        auto french = load_opus(argv[3], 2);
        for (int n = 0; n < 2; ++n) {
            auto result = french->translate("The temperature of the machine is too high.");
            if (result.find("température") == std::string::npos || result.find("élevée") == std::string::npos)
                throw std::runtime_error("OPUS French mismatch: " + result);
            std::cout << "OPUS French cached decoder CPU: " << result << std::endl;
        }
        for (auto * engine : {hymt.get(), opus.get(), french.get()}) {
            engine->cancel(); bool rejected = false;
            try { engine->translate("Must not execute after cancellation"); } catch (const std::runtime_error &) { rejected = true; }
            if (!rejected) throw std::runtime_error("Cancellation was ignored");
        }
        std::cout << "PASS: three translation directions, decoder caching, context reset, cancellation" << std::endl;
        return 0;
    } catch (const std::exception & e) { std::cerr << e.what() << std::endl; return 1; }
}
