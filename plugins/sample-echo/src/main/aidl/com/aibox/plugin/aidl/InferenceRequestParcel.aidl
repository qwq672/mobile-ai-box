package com.aibox.plugin.aidl;
parcelable InferenceRequestParcel { String prompt; int maxTokens; float temperature; float topP; int topK; List<String> stopSequences; List<String> imageUris; String systemPrompt; boolean stream; long timeoutMillis; }
