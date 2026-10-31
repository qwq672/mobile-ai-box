// SettingFieldEntry.aidl
package com.aibox.plugin;

/**
 * 设置字段项（AIDL parcelable）。
 *
 * 跨进程传递的"插件设置 schema 项"，对应 Kotlin 侧 [com.aibox.infra.plugin.SettingField]。
 *
 * 因 AIDL 不支持 Kotlin 中的 `Any?` / lambda 字段，这里只保留可序列化的元数据：
 * - type 用 int 表达 FieldType 序数（SWITCH=0, DROPDOWN=1, SLIDER=2, INPUT=3, GROUP=4）
 * - defaultValue / optionValues 均序列化为 String，由 Kotlin 侧 parse 回原始类型
 * - 不包含 visibleWhen / enabled 等运行期 lambda；这些在 Kotlin 侧 SettingField 上保留
 */
parcelable SettingFieldEntry {
    String key;
    int type;
    String title;
    String description;
    String defaultValue;
    List<String> optionValues;
    List<String> optionLabels;
    float minValue;
    float maxValue;
    float step;
    String group;
}
