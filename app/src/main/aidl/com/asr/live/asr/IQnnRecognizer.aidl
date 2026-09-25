package com.asr.live.asr;
import android.os.Bundle;
interface IQnnRecognizer {
    int pid();
    Bundle initialize(String directory, String language, int threads, long sessionId);
    Bundle accept(in float[] samples);
    Bundle finish();
    void shutdown();
}
