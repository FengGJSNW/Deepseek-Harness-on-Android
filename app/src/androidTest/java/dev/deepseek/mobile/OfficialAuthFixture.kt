package dev.deepseek.mobile

/** Local synthetic DOM; no credentials or SMS requests leave the test WebView. */
internal object OfficialAuthFixture {
    val chat =
        """
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>合成对话 - DeepSeek</title>
        <a href="/a/chat/s/fixture-12345678">合成历史</a>
        <div class="ds-message"><div class="ds-assistant-message-main-content">登录验收成功</div></div>
        <div><div><textarea></textarea><div role="button" class="ds-button--primary"><svg><path d="M8.3125 fixture"/></svg></div></div></div>
        """
            .trimIndent()
    val login =
        """
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>合成登录</title>
        <div id="form"></div><div role="alert" id="error"></div>
        <script>
        window.fixtureCodeRequests=0;window.fixtureLoginRequests=0;
        function show(password){
          document.getElementById('form').innerHTML=password?
            '<input class="ds-input__input" type="text" placeholder="请输入手机号/邮箱地址"><input class="ds-input__input" type="password" placeholder="请输入密码">':
            '<input class="ds-input__input" type="tel" placeholder="请输入手机号"><input class="ds-input__input" type="tel" autocomplete="one-time-code" placeholder="请输入验证码"><div role="button" id="code">发送验证码</div>';
          const mode=document.createElement('div');mode.setAttribute('role','button');mode.textContent=password?'验证码登录':'密码登录';mode.onclick=()=>show(!password);form.append(mode);
          const submit=document.createElement('div');submit.setAttribute('role','button');submit.textContent='登录';form.append(submit);
          if(!password) document.getElementById('code').onclick=()=>{window.fixtureCodeRequests++;document.getElementById('code').textContent='59秒后重试';document.getElementById('code').setAttribute('aria-disabled','true');};
          submit.onclick=()=>{
            window.fixtureLoginRequests++;
            const fields=[...document.querySelectorAll('input.ds-input__input')];
            if(fields[1].value!==(password?'fixture-password':'123456')){error.textContent='凭据不正确，请重试';return;}
            document.body.innerHTML=${org.json.JSONObject.quote(chat)};
            history.replaceState(null,'','/a/chat/s/fixture-12345678');
          };
        }show(false);
        </script>
    """
            .trimIndent()
}
