package com.aibox.plugin.aidl;
import com.aibox.plugin.aidl.PluginDescriptor;
import com.aibox.plugin.aidl.InferenceRequestParcel;
import com.aibox.plugin.aidl.InferenceResultParcel;
import com.aibox.plugin.aidl.InferCallbacksProxy;
interface IPluginInterface {
    PluginDescriptor describe();
    void start();
    void stop();
    InferenceResultParcel inferOnce(in InferenceRequestParcel request);
    InferenceResultParcel inferStreamed(in InferenceRequestParcel request, in InferCallbacksProxy callback);
    void cancel();
    int apiVersion();
}
