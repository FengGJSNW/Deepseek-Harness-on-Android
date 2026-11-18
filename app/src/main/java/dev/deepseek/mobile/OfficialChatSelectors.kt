package dev.deepseek.mobile

/** Public DOM only. Rechecked against DeepSeek main.1994c85c88.js (2026-11-18). */
internal object OfficialChatSelectors {
    val script =
        """
        const dsha = (() => {
          const visible = e => !!e && !e.closest('[hidden],[aria-hidden="true"]') && getComputedStyle(e).display !== 'none';
          const input = [...document.querySelectorAll('textarea')].filter(e => visible(e) && !e.closest('.ds-message,[role="dialog"]') && !/captcha/i.test(e.name || ''))
            .sort((a,b) => Number(b.name === 'search' || b.classList.contains('_27c9245')) - Number(a.name === 'search' || a.classList.contains('_27c9245')))[0];
          const label = e => (e?.getAttribute('aria-label') || e?.getAttribute('title') || e?.textContent || '').trim().toLowerCase();
          const paths = e => [...(e?.querySelectorAll('svg path') || [])].map(p => p.getAttribute('d') || '');
          const send = e => /^(发送|发送消息|send|send message)$/.test(label(e)) || paths(e).some(p => p.startsWith('M8.3125 0.980206'));
          const stop = e => /^(停止生成|停止回答|stop generating|stop response)$/.test(label(e)) || paths(e).some(p => p.startsWith('M2 4.88C2 3.68009'));
          const controls = e => [...(e?.querySelectorAll('button,[role="button"],.ds-button,.ds-icon-button') || [])].filter(visible);
          const primaryControls = host => {
            const candidates = controls(host).filter(e => !e.closest('.ds-message,[role="dialog"]') && (send(e) || stop(e)));
            return candidates.filter(e => !candidates.some(inner => inner !== e && e.contains(inner)));
          };
          let host = input?.closest('._77cefa5,[data-testid="composer"],form');
          let matches = primaryControls(host);
          if (!matches.length) {
            let parent = input?.parentElement;
            for (let i=0; parent && parent !== document.body && i<8; i++, parent=parent.parentElement) {
              matches = primaryControls(parent);
              if (matches.length) { host=parent; break; }
            }
          }
          const primary=matches.length === 1 ? matches[0] : null;
          const enabled=e=>!!e && !e.disabled && e.getAttribute('aria-disabled') !== 'true' && !e.classList.contains('ds-button--disabled') && !e.classList.contains('ds-toggle-button--disabled') && !e.classList.contains('ds-icon-button--disabled');
          const toggle=names=>[...(host?.querySelectorAll('.ds-toggle-button,[aria-pressed]') || [])].find(e=>names.includes(label(e)));
          const selected=e=>e?.getAttribute('aria-pressed')==='true' || !!e?.classList.contains('ds-toggle-button--selected');
          const route=value=>{try {const u=new URL(value,location.origin);return u.origin===location.origin && /^\/a\/[a-zA-Z0-9_-]{1,64}\/s\/[a-zA-Z0-9_-]{8,128}\/?$/.test(u.pathname) ? u.pathname.replace(/\/$/,'') : null;}catch{return null;}};
          const sessions=[...document.querySelectorAll('a[href]')].map(e=>({path:route(e.getAttribute('href')),title:(e.innerText||e.textContent||'对话').trim().slice(0,100)})).filter(e=>e.path).slice(0,300);
          const users=()=>[...document.querySelectorAll('.ds-message.d29f3d7d,[data-message-role="user"]')];
          return {input,host,primary,send,stop,enabled,toggle,selected,route,sessions,users};
        })();
        """
            .trimIndent()
}
