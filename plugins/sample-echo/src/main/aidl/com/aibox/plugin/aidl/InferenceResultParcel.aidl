package com.aibox.plugin.aidl;
parcelable InferenceResultParcel { String text; int tokensGenerated; long latencyMillis; double tokensPerSecond; int finishReason; boolean npuUsed; String errorCode; String errorMessage; }
