package com.aibox.core.plugin.host.discovery
public enum class PluginKind { INFERENCE_BACKEND, CAPABILITY, NPU_DELEGATE }
public data class PluginManifest(public val packageName: String, public val className: String, public val kind: PluginKind, public val versionName: String, public val apiVersion: Int)
