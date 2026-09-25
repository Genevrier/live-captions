#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

struct TranslationStats {
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int64_t first_token_ms = 0;
    int64_t output_tokens = 0;
};

class TranslationEngine {
public:
    virtual ~TranslationEngine() = default;
    virtual std::string translate(const std::string & text) = 0;
    virtual std::string backend() const { return "CPU"; }
    virtual TranslationStats stats() const { return {}; }
    void cancel() { cancelled.store(true); }
    void resetCancellation() { cancelled.store(false); }
protected:
    std::atomic<bool> cancelled{false};
    void checkCancelled() const;
};
std::unique_ptr<TranslationEngine> load_hymt(const std::string & path, int threads,
                                              int batch, int ubatch, bool prefer_opencl,
                                              const std::string & cache_dir);
std::unique_ptr<TranslationEngine> load_opus(const std::string & directory, int threads);
