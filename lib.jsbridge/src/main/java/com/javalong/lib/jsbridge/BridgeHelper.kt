package com.javalong.lib.jsbridge

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue

/**
 * native／js交互处理
 */
class BridgeHelper {
    companion object {
        const val SCHEME = "jsbridge://"
        const val CALL_HANDLE_URL = SCHEME + "call_handle_url"
        const val CALL_BACK_URL = SCHEME + "call_back_url"
        const val PREFEX_CALL_FUNCS_KEY = "jjnative_"
        const val NO_CALL_FUNC_KEY = "-1"
        const val CALL_JSBRIDGEMETHOD_FUNCNAME = "Bridge.executeBridgeFunc('%s')"
        const val CALL_JSCALLBACKMETHOD_FUNCNAME = "Bridge.executeCallFunc('%s','%s')"
    }

    private var bridgeFuncs: HashMap<String, BridgeHandler> = HashMap()
    private var callFuncs: HashMap<String, ResponseCallback> = HashMap()
    private var syncMessages = LinkedBlockingQueue<Message>()
    lateinit var webView: WebView
    protected var syncMessageLoop = false
    var unique = 0


    /**
     * 初始化，做好准备工作
     */
    fun init(webView: WebView, webViewClient: WebViewClient?) {
        this.webView = webView

        //加载bridge.js
        webView.webViewClient = object : WebViewClientProxy(webViewClient) {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                //先去加载js
                webView?.loadUrl("javascript:" + assetFile2Str(webView?.context, "bridge.js"))
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null || webView == null)
                    return super.shouldOverrideUrlLoading(view, url)
                if (!url.startsWith(SCHEME))
                    return super.shouldOverrideUrlLoading(view, url)
                if (url.startsWith(CALL_HANDLE_URL)) {
                    //获取message
                    var uri = Uri.parse(url)
                    var message = uri.getQueryParameter("message")
                    var messages = uri.getQueryParameter("messages")
                    if (!TextUtils.isEmpty(message)) {
                        executeBridgeFunc(JSONObject.parseObject(message, Message::class.java))
                    }
                    if (!TextUtils.isEmpty(messages)) {
                        executeBridgesFunc(messages)
                    }
                } else if (url.startsWith(CALL_BACK_URL)) {
                    //调用方法后回调
                    var uri = Uri.parse(url)
                    var messages = uri.getQueryParameter("messages")
                    executeCallFunc(messages)
                }
                return true
            }
        }
    }

    private fun assetFile2Str(c: Context, urlStr: String): String? {
        var `in`: InputStream? = null
        try {
            `in` = c.assets.open(urlStr)
            val bufferedReader = BufferedReader(InputStreamReader(`in`!!))
            var line: String? = null
            val sb = StringBuilder()
            do {
                line = bufferedReader.readLine()
                if (line != null && !line.matches("^\\s*\\/\\/.*".toRegex())) {
                    sb.append(line)
                }
            } while (line != null)

            bufferedReader.close()
            `in`.close()

            return sb.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (`in` != null) {
                try {
                    `in`.close()
                } catch (e: IOException) {
                }

            }
        }
        return null
    }

    private fun executeCallFunc(messages: String) {

        var jsonArr = JSONObject.parseArray(messages)
        var sync = false
        for (i in 0 until jsonArr.size) {
            var json = jsonArr.getJSONObject(i)
            var data = json.getJSONObject("data")
            var id = json.getString("id")
            if (callFuncs.containsKey(id)) {
                callFuncs[id]?.call(json)
            }
            if (data.getBoolean("sync")) {
                sync = true
            }
        }

        //每次执行完一次callback 就去同步消息中获取数据
        if (sync) {
            var nextMsg = syncMessages.poll()
            if (nextMsg == null) {
                syncMessageLoop = false
                return
            }
            webView.loadUrl("javascript:" + String.format(CALL_JSBRIDGEMETHOD_FUNCNAME, URLEncoder.encode(JSONObject.toJSONString(nextMsg), "utf-8")))
        }
    }

    /**
     * 执行多个bridge
     */
    private fun executeBridgesFunc(messages: String) {
        var msgs = JSONObject.parseArray(messages, Message::class.java)
        msgs.forEach({ msg ->
            executeBridgeFunc(msg)
        })
    }

    /**
     * 获取参数，执行本地注册方法
     */
    private fun executeBridgeFunc(msg: Message) {
        if (bridgeFuncs.containsKey(msg.handlerName)) {
            bridgeFuncs.get(msg.handlerName)?.call(msg.data, object : ResponseCallback {
                override fun call(data: JSONObject?) {
                    var newData = JSONObject()
                    if (data != null) {
                        newData = data
                        newData.put("sync", msg.sync)
                    } else {
                        newData.put("sync", msg.sync)
                    }

                    webView.loadUrl("javascript:" + String.format(CALL_JSCALLBACKMETHOD_FUNCNAME, URLEncoder.encode(JSONObject.toJSONString(newData), "utf-8"), msg.callbackKey))
                }
            })
        }
    }

    fun registerHandler(handlerName: String, handler: BridgeHandler) {
        bridgeFuncs.put(handlerName, handler)
    }

    private fun pushCallFunc(callFunc: ResponseCallback): String {
        var funcKey = NO_CALL_FUNC_KEY
        if (callFunc != null) {
            funcKey = PREFEX_CALL_FUNCS_KEY + this.unique + System.currentTimeMillis()
            this.callFuncs[funcKey] = callFunc
        }
        return funcKey
    }

    fun callSyncHandler(handlerName: String, param: JSONObject, callback: ResponseCallback) {
        var funcKey = pushCallFunc(callback)
        this.syncMessages.offer(Message(handlerName, param, funcKey, true))
        this.unique++
        if (!syncMessageLoop) {
            syncMessageLoop = true
            //调用js方法
            var msg = syncMessages.poll()
            if (msg == null) {
                syncMessageLoop = false
                return
            }
            webView.loadUrl("javascript:" + String.format(CALL_JSBRIDGEMETHOD_FUNCNAME, URLEncoder.encode(JSONObject.toJSONString(msg), "utf-8")))
        }
    }

    fun callAsyncHandler(handlerName: String, param: JSONObject, callback: ResponseCallback) {
        var funcKey = pushCallFunc(callback)
        var msg = Message(handlerName, param, funcKey, true)
        this.unique++
        //调用js方法
        webView.loadUrl("javascript:" + String.format(CALL_JSBRIDGEMETHOD_FUNCNAME, URLEncoder.encode(JSONObject.toJSONString(msg), "utf-8")))
    }
}