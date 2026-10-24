// IChatModuleInterface.aidl
package com.aibox.plugin;

import android.os.ParcelFileDescriptor;

/**
 * 聊天模块对外接口(模块侧实现,宿主侧调用)。
 *
 * 与 IPluginInterface 的区别:
 * - IPluginInterface 是"推理框架插件"的运行时接口(loadModel / infer / streamInfer),
 *   模块 APK 不实现它——模块内不打包 llama.cpp,推理由宿主的 InferenceBackend 完成。
 * - IChatModuleInterface 是"模型容器模块"的接口:宿主绑定后只用来
 *   1) 拉取 module.json 元数据(JSON 字符串);
 *   2) 询问模型文件大小(预估复制时间);
 *   3) 拿到模型文件的 ParcelFileDescriptor(零拷贝读 assets)。
 *
 * 发现顺序:
 * 1. 宿主 PackageManager 扫描 action "com.aibox.action.CHAT_MODULE" 的 Service,
 *    读 meta-data 快速过滤(name/version/model.sizeMb/architecture/recommendedBackend);
 * 2. 宿主 bindService 到符合要求的模块;
 * 3. ServiceConnection.onServiceConnected 拿到 IBinder → asInterface() 转为本接口;
 * 4. 调 getModuleMetadata() 拿权威 module.json;
 * 5. 调 openModelFileDescriptor() 拿 ParcelFileDescriptor,复制到宿主私有目录;
 * 6. unbindService;后续由宿主的 InferenceBackend 加载该本地路径。
 *
 * 设计约束:
 * - 不传 InputStream/Reader 跨进程(AIDL 不支持);
 * - 不传 byte[](485MB 会爆 binder 缓冲);
 * - 用 ParcelFileDescriptor 让宿主直接 read(2),走内核管道,零拷贝。
 *
 * 与宿主 com.aibox.plugin.IPluginInterface 共享同一个 package(com.aibox.plugin)
 * 是有意的:将来发布时可以把这两个接口合并到一个 AAR 分发给第三方模块开发者。
 */
interface IChatModuleInterface {
    /**
     * 返回 assets/module.json 的原始内容(UTF-8 JSON 字符串)。
     * 宿主按本接口契约解析,失败时返回空串("")。
     */
    String getModuleMetadata();

    /**
     * 返回模型文件字节数;失败返回 -1。
     * 宿主据此估算复制耗时与目标盘剩余空间。
     */
    long getModelFileSize();

    /**
     * 返回模型文件的 ParcelFileDescriptor。
     *
     * 实现侧:用 ParcelFileDescriptor.fromInputStream(assets.open(fileName)) 包装。
     * 调用侧:拿到 pfd 后用 FileInputStream(pfd.getFileDescriptor()) 读取,
     * 读完后必须 close pfd。
     *
     * 失败返回 null(读不到文件名 / assets 资源不存在等)。
     * 注意 null 跨 AIDL 是合法的(@Nullable 默认行为)。
     */
    ParcelFileDescriptor openModelFileDescriptor();
}
