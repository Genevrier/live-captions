package com.asr.live.asr;
import android.os.Bundle;
interface IQnnRecognizer {
    int pid();
    void initialize(String directory, String language, int threads);
    Bundle accept(in float[] samples);
    void shutdown();
}
