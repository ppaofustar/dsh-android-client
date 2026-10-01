package com.dsh.chat;

/**
 * 注入到页面的 JS（文档启动时执行，早于页面脚本）。
 *
 * <p>包含两块：
 * <ol>
 *   <li><b>宽屏模式</b>：当原生侧开关打开时，把布局视口宽度强制为一个较宽的值，
 *       让 DSH 以桌面版布局渲染再缩放适配屏幕。窄屏下设置面板的排版需要它。</li>
 *   <li><b>任务状态钩子</b>：挂钩 WebSocket，监听 DSH 的 {@code api-session/status}
 *       事件（{@code true}=开始运行、{@code false}=本轮结束），用于任务完成通知。</li>
 * </ol>
 */
final class JsHook {

    private JsHook() {
    }

    /** 宽屏模式下使用的布局视口宽度（px）。 */
    static final int WIDE_VIEWPORT_WIDTH = 760;

    static final String SCRIPT = "(function(){"
            // ---------- 1. 宽屏模式 ----------
            + "try{"
            + "(function(){"
            + "var wide=false;"
            + "try{wide=!!(window.DSHNative&&window.DSHNative.isWideMode&&window.DSHNative.isWideMode());}catch(e){}"
            + "if(!wide)return;"
            + "var W=" + WIDE_VIEWPORT_WIDTH + ";"
            + "function apply(){"
            + "try{"
            + "var h=document.head||document.documentElement;if(!h)return;"
            + "var m=document.querySelector('meta[name=\"viewport\"]');"
            + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');h.appendChild(m);}"
            + "var sw=(window.screen&&screen.width)?screen.width:W;"
            + "m.setAttribute('content','width='+W+',initial-scale='+(sw/W)+',minimum-scale=0.2,maximum-scale=5,user-scalable=yes');"
            + "}catch(e){}"
            + "}"
            + "apply();"
            + "try{document.addEventListener('DOMContentLoaded',apply);}catch(e){}"
            + "})();"
            + "}catch(e){}"
            // ---------- 2. 任务状态钩子 ----------
            + "try{"
            + "if(!window.__dshHookInstalled){"
            + "window.__dshHookInstalled=true;"
            + "var Native=window.WebSocket;"
            + "if(Native){"
            + "var running={};"
            + "var push=function(state,sid){"
            + "try{if(window.DSHNative&&window.DSHNative.onTaskState)window.DSHNative.onTaskState(String(sid),state);}catch(e){}"
            + "};"
            + "var handle=function(text){"
            + "if(typeof text!=='string'||text.indexOf('api-session/status')===-1)return;"
            + "var msg;try{msg=JSON.parse(text);}catch(e){return;}"
            + "if(!msg||msg.type!=='item')return;"
            + "var v=msg.value;if(!v||v.event!=='api-session/status')return;"
            + "var a=v.args||[];if(a.length<2)return;"
            + "if(a[0]===undefined||a[0]===null)return;"
            + "var sid=String(a[0]);"
            + "if(a[1]===true){if(!running[sid]){running[sid]=true;push('start',sid);}}"
            + "else{if(running[sid]){delete running[sid];push('done',sid);}}"
            + "};"
            + "var Hooked=function(url,protocols){"
            + "var ws=(protocols===undefined)?new Native(url):new Native(url,protocols);"
            + "try{ws.addEventListener('message',function(ev){if(typeof ev.data==='string')handle(ev.data);});}catch(e){}"
            + "return ws;"
            + "};"
            + "Hooked.prototype=Native.prototype;"
            + "Hooked.CONNECTING=Native.CONNECTING;Hooked.OPEN=Native.OPEN;"
            + "Hooked.CLOSING=Native.CLOSING;Hooked.CLOSED=Native.CLOSED;"
            + "window.WebSocket=Hooked;"
            + "}"
            + "}"
            + "}catch(e){}"
            + "})();";
}
