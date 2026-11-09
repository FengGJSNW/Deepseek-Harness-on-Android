package dev.deepseek.mobile

import org.json.JSONObject

internal data class OfficialSession(val path: String, val title: String)

internal data class OfficialMessage(val role: String, val text: String)

internal data class OfficialSnapshot(
    val url: String = "",
    val title: String = "DeepSeek",
    val authenticated: Boolean = false,
    val sessions: List<OfficialSession> = emptyList(),
    val messages: List<OfficialMessage> = emptyList(),
    val unsupported: Boolean = false,
    val generating: Boolean = false,
    val partialHistory: Boolean = false,
    val deepThinking: Boolean = false,
    val search: Boolean = false,
    val thinkingAvailable: Boolean = false,
    val searchAvailable: Boolean = false,
    val attachmentsAvailable: Boolean = false,
)

/** Only allow same-origin session paths. No tokens, storage, cookies or network interception. */
internal object OfficialChatDom {
    fun sessionPath(value: String): Boolean = value.matches(Regex("/a/chat/s/[a-zA-Z0-9-]{8,80}"))

    fun parse(v: JSONObject): OfficialSnapshot {
        val sessions = v.optJSONArray("sessions")
        val messages = v.optJSONArray("messages")
        return OfficialSnapshot(
            url = v.optString("url"),
            title = v.optString("title", "DeepSeek"),
            authenticated = v.optBoolean("authenticated"),
            sessions =
                (0 until (sessions?.length() ?: 0))
                    .mapNotNull {
                        val item = sessions!!.getJSONObject(it)
                        item.optString("path").takeIf(::sessionPath)?.let { path ->
                            OfficialSession(path, item.optString("title").take(100))
                        }
                    }
                    .distinctBy { it.path },
            messages =
                (0 until (messages?.length() ?: 0)).mapNotNull {
                    val item = messages!!.getJSONObject(it)
                    val role = item.optString("role")
                    if (role !in setOf("user", "assistant", "system")) null
                    else OfficialMessage(role, item.optString("text").take(60000))
                },
            unsupported = v.optBoolean("unsupported"),
            generating = v.optBoolean("generating"),
            partialHistory = v.optBoolean("partialHistory"),
            deepThinking = v.optBoolean("deepThinking"),
            search = v.optBoolean("search"),
            thinkingAvailable = v.optBoolean("thinkingAvailable"),
            searchAvailable = v.optBoolean("searchAvailable"),
            attachmentsAvailable = v.optBoolean("attachmentsAvailable"),
        )
    }

    // Selectors inspected in the user's Edge page. Content is data and never enters the Work agent.
    val snapshotScript =
        """
        (() => {
          if (location.origin !== 'https://chat.deepseek.com') return null;
          if (!document.body) return null;
          if (!window.__dshaOfficialObserver) {
            window.__dshaOfficialDirty = true;
            window.__dshaOfficialObserver = new MutationObserver(() => window.__dshaOfficialDirty = true);
            window.__dshaOfficialObserver.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','disabled','aria-disabled','aria-pressed']});
          }
          if (!window.__dshaOfficialDirty && window.__dshaOfficialUrl === location.href) return null;
          window.__dshaOfficialDirty = false; window.__dshaOfficialUrl = location.href;
          const input = document.querySelector('textarea:not([name*=captcha])');
          if (input && !window.__dshaOfficialVisible && !window.__dshaOfficialWide && document.querySelector('meta[name=viewport]')) {
            document.querySelector('meta[name=viewport]').content='width=1100,initial-scale=1';
            window.__dshaOfficialWide=true;
          }
          const sessions = [...document.querySelectorAll('a[href^="/a/chat/s/"]')].slice(0,300)
            .map(e => ({path:e.getAttribute('href'),title:(e.innerText||e.textContent||'对话').trim().slice(0,100)}));
          const nodes = [...document.querySelectorAll('.ds-message')].slice(-100);
          let unknown = false;
          const text = root => {
            const escape = value => value.replace(/([\`*_[\]#~<>])/g,'\$1');
            const walk = n => {
              if (n.nodeType === 3) return escape(n.textContent);
              if (n.nodeType !== 1 || n.matches('button,[role=button],svg,script,style')) return '';
              if (n.tagName === 'PRE') {
                const code=n.querySelector('code')||n;
                const source=code.innerText;
                const fence='`'.repeat(Math.max(3,...(source.match(/`+/g)||[]).map(s=>s.length+1)));
                const lang=(code.className||'').match(/(?:language|lang)-([a-zA-Z0-9_+-]+)/)?.[1]||'';
                return '\n'+fence+lang+'\n'+source+'\n'+fence+'\n';
              }
              if (n.tagName === 'TABLE') {
                const rows=[...n.querySelectorAll('tr')].filter(r=>r.closest('table')===n);
                const cells=r=>[...r.children].filter(c=>/^(TD|TH)$/.test(c.tagName));
                const row=r=>'| '+cells(r).map(c=>[...c.childNodes].map(walk).join('').trim().replace(/\|/g,'\|').replace(/\n/g,'<br>')).join(' | ')+' |';
                if(!rows.length)return '';
                const align=c=>c.getAttribute('align')||c.style.textAlign;
                return '\n'+row(rows[0])+'\n| '+cells(rows[0]).map(c=>align(c)==='right'?'---:':align(c)==='center'?':---:':'---').join(' | ')+' |\n'+rows.slice(1).map(row).join('\n')+'\n';
              }
              if (n.tagName === 'BR') return '\n';
              if (n.tagName === 'IMG' && /^https:/.test(n.src)) return '!['+escape(n.alt||'图片')+'](<'+n.src.replace(/>/g,'%3E')+'>)';
              if (n.tagName === 'INPUT' && n.type==='checkbox') return n.checked?'[x] ':'[ ] ';
              const body = [...n.childNodes].map(walk).join('');
              if (n.tagName === 'CODE') {
                const fence='`'.repeat(Math.max(1,...(n.innerText.match(/`+/g)||[]).map(s=>s.length+1)));
                return fence+' '+n.innerText+' '+fence;
              }
              if (n.tagName === 'A' && /^https?:/.test(n.href)) return '['+body+'](<'+n.href.replace(/>/g,'%3E')+'>)';
              if (/^H[1-6]$/.test(n.tagName)) return '\n'+'#'.repeat(Number(n.tagName[1]))+' '+body+'\n';
              if (/^(STRONG|B)$/.test(n.tagName)) return '**'+body+'**';
              if (/^(EM|I)$/.test(n.tagName)) return '*'+body+'*';
              if (/^(S|DEL)$/.test(n.tagName)) return '~~'+body+'~~';
              if (n.tagName === 'BLOCKQUOTE') return '\n'+body.trim().split('\n').map(line=>'> '+line).join('\n')+'\n';
              if (n.tagName === 'LI') {
                const parent=n.parentElement;
                const marker=parent?.tagName==='OL'?(Number(parent.getAttribute('start')||1)+[...parent.children].indexOf(n))+'. ':'- ';
                return '\n'+marker+body.trim().replace(/\n/g,'\n  ');
              }
              if (n.tagName === 'HR') return '\n---\n';
              return /^(P|DIV|UL|OL)$/.test(n.tagName) ? '\n'+body+'\n' : body;
            };
            return walk(root).replace(/\n{3,}/g,'\n\n').trim().slice(0,60000);
          };
          const messages = nodes.map(e => {
            const assistant = e.querySelector('.ds-assistant-message-main-content');
            const user = e.classList.contains('d29f3d7d') || e.getAttribute('data-message-role')==='user';
            if (assistant) return {role:'assistant',text:text(assistant)};
            if (user) {const body=e.querySelector('.eafda4ae,.fbb737a4')||e;return {role:'user',text:body.innerText.trim().slice(0,60000)};}
            unknown = true; return {role:'system',text:text(e)};
          }).filter(Boolean);
          const primary = input?.parentElement?.parentElement?.querySelector('.ds-button--primary[role=button]');
          const host=input?.parentElement?.parentElement;
          const toggle=names=>[...(host?.querySelectorAll('.ds-toggle-button,[aria-pressed]')||[])].find(e=>names.some(n=>(e.textContent||'').trim().toLowerCase()===n));
          const thinking=toggle(['深度思考','deepthink','deepthink (r1)']);
          const search=toggle(['智能搜索','联网搜索','search']);
          const selected=e=>e?.getAttribute('aria-pressed')==='true'||!!e?.classList.contains('ds-toggle-button--selected');
          // Busy button switches to a square path; do not infer state from user's draft text.
          const arrow = primary?.querySelector('svg path')?.getAttribute('d')?.startsWith('M8.3125');
          return {url:location.href,title:document.title.replace(/ - DeepSeek$/,''),authenticated:!!primary && !/^\/(sign_|auth|login)/.test(location.pathname),
            sessions,messages,unsupported:!input||!primary,partialHistory:unknown,generating:!!primary && !arrow,
            deepThinking:selected(thinking),search:selected(search),thinkingAvailable:!!thinking,searchAvailable:!!search,
            attachmentsAvailable:!!host?.querySelector('input[type=file]')};
        })()
        """
            .trimIndent()

    fun sendScript(text: String) =
        """
        (() => {
          const input = document.querySelector('textarea:not([name*=captcha])');
          if (!input || location.origin !== 'https://chat.deepseek.com') return {error:'请先登录官方聊天'};
          const button = input.parentElement.parentElement.querySelector('.ds-button--primary[role=button]');
          if (!button) return {error:'未找到官方发送按钮，请重试连接'};
          const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;
          set.call(input,${JSONObject.quote(text)});
          input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:${JSONObject.quote(text)}}));
          input.dispatchEvent(new Event('change',{bubbles:true}));
          window.__dshaOfficialDirty = true;
          return {prepared:true,url:location.href,users:document.querySelectorAll('.ds-message.d29f3d7d,[data-message-role="user"]').length};
        })()
    """
            .trimIndent()

    val readyToSendScript =
        """
        (() => {
          const input = document.querySelector('textarea:not([name*=captcha])');
          const button = input?.parentElement?.parentElement?.querySelector('.ds-button--primary[role=button]');
          if (!button || !button.querySelector('svg path')?.getAttribute('d')?.startsWith('M8.3125') || button.classList.contains('ds-button--disabled') || button.getAttribute('aria-disabled')==='true')
            return {error:'官方正在处理，请稍后重试'};
          return {ready:true};
        })()
        """
            .trimIndent()
    val clickSendScript =
        """
        (() => {
          const input = document.querySelector('textarea:not([name*=captcha])');
          const button = input?.parentElement?.parentElement?.querySelector('.ds-button--primary[role=button]');
          if (!button || !button.querySelector('svg path')?.getAttribute('d')?.startsWith('M8.3125') || button.classList.contains('ds-button--disabled') || button.getAttribute('aria-disabled')==='true')
            return {error:'官方正在处理，请稍后重试'};
          button.click(); return {sent:true};
        })()
        """
            .trimIndent()

    fun modeScript(mode: String): String {
        require(mode in listOf("thinking", "search"))
        val names =
            if (mode == "thinking") "['深度思考','deepthink','deepthink (r1)']"
            else "['智能搜索','联网搜索','search']"
        return """(() => {
            if(location.origin!=='https://chat.deepseek.com')return {error:'官方连接无效'};
            const host=document.querySelector('textarea:not([name*=captcha])')?.parentElement?.parentElement;
            const button=[...(host?.querySelectorAll('.ds-toggle-button,[aria-pressed]')||[])].find(e=>$names.includes((e.textContent||'').trim().toLowerCase()));
            if(!button||button.getAttribute('aria-disabled')==='true'||button.classList.contains('ds-toggle-button--disabled'))return {error:'官方模式暂不可用'};
            button.click();window.__dshaOfficialDirty=true;return {changed:true};
        })()"""
            .trimIndent()
    }

    fun acknowledgementScript(text: String, before: Int, previousUrl: String) =
        """(() => {
        if(location.origin!=='https://chat.deepseek.com')return {accepted:false};
        const users=[...document.querySelectorAll('.ds-message.d29f3d7d,[data-message-role="user"]')];
        const last=users.at(-1)?.innerText?.trim()||'';
        return {accepted:users.length>$before || (location.href!==${JSONObject.quote(previousUrl)}&&!!last&&last.includes(${JSONObject.quote(text.trim().take(120))}))};
    })()"""
            .trimIndent()

    val beginAttachmentsScript =
        """
        (() => {
                if(location.origin!=='https://chat.deepseek.com'||!document.querySelector('textarea')?.parentElement?.parentElement?.querySelector('input[type=file]'))return {error:'官方附件入口暂不可用'};
                window.__dshaOfficialUpload={files:[],parts:[]};return {ready:true};
            })()
        """
            .trimIndent()

    fun attachmentChunkScript(base64: String) =
        """(() => {
        const upload=window.__dshaOfficialUpload;if(!upload)return {error:'附件准备已取消'};
        const value=atob(${JSONObject.quote(base64)});upload.parts.push(Uint8Array.from(value,c=>c.charCodeAt(0)));return {ready:true};
    })()"""
            .trimIndent()

    fun finishAttachmentScript(name: String, mime: String) =
        """(() => {
        const upload=window.__dshaOfficialUpload;if(!upload)return {error:'附件准备已取消'};
        upload.files.push(new File(upload.parts,${JSONObject.quote(name)}, {type:${JSONObject.quote(mime)}}));upload.parts=[];return {ready:true};
    })()"""
            .trimIndent()

    val commitAttachmentsScript =
        """
        (() => {
                const input=document.querySelector('textarea')?.parentElement?.parentElement?.querySelector('input[type=file]'),upload=window.__dshaOfficialUpload;
                if(!input||!upload)return {error:'官方附件入口暂不可用'};
                const transfer=new DataTransfer();upload.files.forEach(file=>transfer.items.add(file));input.files=transfer.files;
                input.dispatchEvent(new Event('change',{bubbles:true}));delete window.__dshaOfficialUpload;window.__dshaOfficialDirty=true;return {ready:true};
            })()
        """
            .trimIndent()
}
