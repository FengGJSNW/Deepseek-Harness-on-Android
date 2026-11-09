package dev.deepseek.mobile

import org.json.JSONObject

internal data class OfficialAuthSnapshot(
    val mode: String = "loading",
    val error: String = "",
    val codeLabel: String = "发送验证码",
    val codeEnabled: Boolean = false,
    val challenge: Boolean = false,
)

/** Public sign-in DOM only. Never returns input values, cookies, tokens or private storage. */
internal object OfficialAuthDom {
    private val helpers =
        """
        if (location.origin !== 'https://chat.deepseek.com') return {error:'登录页面来源不正确'};
        const fields=()=>[...document.querySelectorAll('input.ds-input__input')];
        const button=(...labels)=>[...document.querySelectorAll('[role=button],button')].find(e=>labels.includes((e.innerText||e.textContent||'').trim()));
        const enabled=e=>!!e && !e.disabled && e.getAttribute('aria-disabled')!=='true' && !e.classList.contains('ds-button--disabled');
        const set=(e,value)=>{
          if(!e) throw Error('官方登录表单已变化，请重试');
          Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,value);
          e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
        };
        """
            .trimIndent()

    // Field placeholders and role=button text inspected on the public sign_in page, 2026-11-01.
    val snapshotScript =
        """
        (()=>{
          $helpers
          const composer=document.querySelector('textarea:not([name*=captcha])');
          const send=composer?.parentElement?.parentElement?.querySelector('.ds-button--primary[role=button]');
          if(send && !/^\/(sign_|auth|login)/.test(location.pathname)) return {mode:'authenticated'};
          const password=fields().find(e=>e.type==='password');
          const code=fields().find(e=>e.autocomplete==='one-time-code'||e.placeholder==='请输入验证码'||e.placeholder==='Code');
          const phone=fields().find(e=>e.type==='tel'&&e!==code);
          const mode=password?'password':code&&phone?'sms':'loading';
          const codeButton=button('发送验证码','Send code') || [...document.querySelectorAll('[role=button],button')].find(e=>/\d+\s*[s秒]/.test(e.innerText||''));
          const errors=[...document.querySelectorAll('.ds-form-item__feedback,[role=alert],.ds-toast__content')]
            .map(e=>(e.innerText||'').trim()).filter(Boolean).join(' ').slice(0,220);
          const challengeNodes=[...document.querySelectorAll('[class*=geetest_panel],[class*=captcha],[id*=captcha],iframe[src*=captcha],iframe[src*=verify]')];
          const challenge=challengeNodes.some(e=>{const r=e.getBoundingClientRect();return r.width>=160&&r.height>=80&&getComputedStyle(e).visibility!=='hidden';});
          return {mode,error:errors,codeLabel:(codeButton?.innerText||'').match(/\d+/)?.[0]?((codeButton.innerText.match(/\d+/)||[])[0]+'秒后重试'):'发送验证码',codeEnabled:enabled(codeButton),challenge};
        })()
    """
            .trimIndent()

    fun modeScript(mode: String) =
        """
        (()=>{
          $helpers
          const password=fields().some(e=>e.type==='password');
          if(password===${mode == "password"}) return {ready:true};
          const e=${if (mode == "password") "button('密码登录','Password login','Login with password')" else "button('验证码登录','Code login','Login with code')"};
          if(!enabled(e)) return {error:'官方页面尚未准备好，请稍后重试'};
          e.click();return {ready:true};
        })()
    """
            .trimIndent()

    fun prepareScript(mode: String, account: String, secret: String) =
        """
        (()=>{
          $helpers
          try {
            const user=fields().find(e=>${if(mode=="sms") "e.type==='tel'&&e.autocomplete!=='one-time-code'&&e.placeholder!=='请输入验证码'&&e.placeholder!=='Code'" else "['text','email','tel'].includes(e.type)&&e.autocomplete!=='one-time-code'"});
            const credential=fields().find(e=>${if(mode=="sms") "e.autocomplete==='one-time-code'||e.placeholder==='请输入验证码'||e.placeholder==='Code'" else "e.type==='password'"});
            if(!user||!credential) return {error:'官方登录表单尚未准备好'};
            set(user,${JSONObject.quote(account)});set(credential,${JSONObject.quote(secret)});
            return {prepared:true};
          } catch(_) {return {error:'官方登录表单已变化，请重试'};}
        })()
    """
            .trimIndent()

    fun clickScript(code: Boolean) =
        """
        (()=>{
          $helpers
          const e=${if(code) "button('发送验证码','Send code')" else "button('登录','Log in','Sign in')"};
          if(!enabled(e)) return {error:'官方按钮暂不可用，请检查输入后重试'};
          e.click();return {submitted:true};
        })()
    """
            .trimIndent()

    val clearFieldsScript =
        """
        (()=>{if(location.origin!=='https://chat.deepseek.com')return false;
          document.querySelectorAll('input.ds-input__input').forEach(e=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,'');e.dispatchEvent(new Event('input',{bubbles:true}));});return true;})()
        """
            .trimIndent()

    /**
     * Show only a provider security widget; the login form and its browser controls stay hidden.
     */
    val showVerificationScript =
        """
        (()=>{
          if(location.origin!=='https://chat.deepseek.com')return false;
          const nodes=[...document.querySelectorAll('[class*=geetest_panel],[class*=captcha],[id*=captcha],iframe[src*=captcha],iframe[src*=verify]')];
          const node=nodes.find(e=>{const r=e.getBoundingClientRect();return r.width>=160&&r.height>=80&&getComputedStyle(e).visibility!=='hidden';});
          if(!node)return false;
          window.__dshaVerificationNode=node;window.__dshaVerificationStyle=node.getAttribute('style');
          node.setAttribute('data-dsha-verification','');
          const style=document.createElement('style');style.id='dsha-verification-only';
          style.textContent='body *{visibility:hidden!important}[data-dsha-verification],[data-dsha-verification] *{visibility:visible!important}';
          document.head.append(style);
          node.style.setProperty('position','fixed','important');node.style.setProperty('left','50%','important');
          node.style.setProperty('top','50%','important');node.style.setProperty('transform','translate(-50%,-50%)','important');
          return true;
        })()
        """
            .trimIndent()
    val hideVerificationScript =
        """
        (()=>{document.getElementById('dsha-verification-only')?.remove();const node=window.__dshaVerificationNode;
          if(node){node.removeAttribute('data-dsha-verification');if(window.__dshaVerificationStyle===null)node.removeAttribute('style');else node.setAttribute('style',window.__dshaVerificationStyle);}
          window.__dshaVerificationNode=null;return true;})()
        """
            .trimIndent()

    // Explicit user sign-out removes official-origin web storage, never exports its values.
    val clearOriginScript =
        """
        (()=>{if(location.origin!=='https://chat.deepseek.com')return false;
          localStorage.clear();sessionStorage.clear();return true;})()
        """
            .trimIndent()

    fun parse(value: JSONObject) =
        OfficialAuthSnapshot(
            mode = value.optString("mode", "loading"),
            error = value.optString("error"),
            codeLabel = value.optString("codeLabel", "发送验证码"),
            codeEnabled = value.optBoolean("codeEnabled"),
            challenge = value.optBoolean("challenge"),
        )
}
