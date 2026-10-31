package com.aibox.plugin.aidl;
import com.aibox.plugin.aidl.InferenceResultParcel;
interface InferCallbacksProxy {
    void onToken(String token);
    void onProgress(float progress);
    void onWarning(String message);
    void onComplete(in InferenceResultParcel result);
}
