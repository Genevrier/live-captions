#pragma once
#include <atomic>
#include <memory>
#include <string>

class TranslationEngine {
public:
    virtual ~TranslationEngine() = default;
    virtual std::string translate(const std::string & text) = 0;
    void cancel() { cancelled.store(true); }
protected:
    std::atomic<bool> cancelled{false};
    void checkCancelled() const;
};
std::unique_ptr<TranslationEngine> load_hymt(const std::string & path, int threads);
std::unique_ptr<TranslationEngine> load_opus(const std::string & directory, int threads);
