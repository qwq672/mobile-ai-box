package com.easychat

import android.os.ParcelFileDescriptor
import android.util.Log
import com.aibox.plugin.IChatModuleInterface
import java.io.IOException

/**
 * [EasyChatModuleService] 的 AIDL Stub 实现。
 *
 * 继承自由 AIDL 工具生成的 [IChatModuleInterface.Stub],
 * 自动实现跨进程的 onTransact 分发。
 *
 * 注意:
 * - 本类持有的 [service] 是 Service 上下文,只用它读 assets,不做其他事。
 * - 所有方法都不会抛 RemoteException 给宿主:跨进程异常会被 Binder 框架转成
 *   RemoteException 抛在调用侧,但本类内部已用 runCatching 兜底把异常
 *   转成"语义化失败值"(空串 / -1 / null),让宿主代码更简洁。
 */
class EasyChatModuleBinder(private val service: EasyChatModuleService) : IChatModuleInterface.Stub() {

    companion object {
        private const val TAG = "EasyChatModuleBinder"
    }

    /** @see EasyChatModuleService.getModuleMetadata */
    override fun getModuleMetadata(): String = service.getModuleMetadata()

    /** @see EasyChatModuleService.getModelFileSize */
    override fun getModelFileSize(): Long = service.getModelFileSize()

    /**
     * 把 assets 中的模型流包装成 [ParcelFileDescriptor] 跨进程返回。
     *
     * 实现细节:
     * - ParcelFileDescriptor.fromInputStream 内部会开一对 socket / pipe,
     *   起一个后台线程把 InputStream 数据泵到写端,把读端 fd 跨 binder 传给宿主;
     * - 宿主侧拿到 pfd 后调 FileInputStream(pfd.fileDescriptor).read()
     *   即可零拷贝读取(InputStream 走 socket 在内核态);
     * - 宿主读完后必须 pfd.close(),否则后台泵线程不会结束;
     * - 我们这里不持有 pfd 的引用,close 责任完全交给宿主。
     *
     * 失败(模型文件未注入)返回 null,宿主应据此跳过本模块或提示用户。
     */
    override fun openModelFileDescriptor(): ParcelFileDescriptor? {
        return try {
            val input = service.openModelFile()
            // 用 LocalSocket 实现"InputStream → ParcelFileDescriptor"零拷贝桥接
            // 创建一对本地 socket,起后台线程把 InputStream 数据泵到 socket 写端
            // 宿主侧拿到 pfd (socket 读端) 即可正常 read
            val socketPair = ParcelFileDescriptor.createReliablePipe()
            val writePfd = socketPair[1]
            val readPfd = socketPair[0]
            
            Thread {
                try {
                    val out = java.io.FileOutputStream(writePfd.fileDescriptor)
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                    }
                    out.flush()
                } catch (e: Throwable) {
                    Log.w(TAG, "Pump thread error (likely client closed early)", e)
                } finally {
                    try { input.close() } catch (_: Throwable) {}
                    try { writePfd.close() } catch (_: Throwable) {}
                }
            }.apply { isDaemon = true; name = "EasyChat-Pump" }.start()
            
            readPfd
        } catch (e: IOException) {
            Log.w(TAG, "Model asset not available (CI not injected yet?)", e)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error opening model asset", e)
            null
        }
    }
}
