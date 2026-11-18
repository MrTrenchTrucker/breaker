// Registration view: the DOM part of the registration module. Everything is built inside the root it
// is given: no document lookups by id, no window, no browser storage. Every piece of text goes in with
// textContent, so a hostile sentence from the server ends up as literal text and never as markup.
// The install code field is optional: it may be left blank, and it can be left out of the view altogether.
// The fields live in a real form, so Enter submits exactly like the button. The status line is a live
// region, so a screen reader announces a refusal. Field clearing follows the rule: the password is
// cleared on every run, the install code only on success - a failure may be a typo the person has to
// fix, and making them retype forty three characters is the wrong trade.
// The flow arrives ready-made through the `register` option, so this file imports no flow module.

export const VIEW_TEXTS = Object.freeze({
  username_label: 'Username',
  password_label: 'Password',
  install_code_label: 'Install code (optional)',
  submit: 'Create account',
  created: 'Your account is ready, and your keys are stored.',
});

export function mountRegister(root, { register, onRegistered, installCodeField = true }) {
  const username = document.createElement('input');
  username.type = 'text';
  username.setAttribute('aria-label', VIEW_TEXTS.username_label);
  username.setAttribute('autocomplete', 'username');

  const password = document.createElement('input');
  password.type = 'password';
  password.setAttribute('aria-label', VIEW_TEXTS.password_label);
  password.setAttribute('autocomplete', 'new-password');

  // Optional field: not required, and blank means "no code", which the flow sends as absent.
  let installCode = null;
  if (installCodeField) {
    installCode = document.createElement('input');
    installCode.type = 'text';
    installCode.required = false;
    installCode.setAttribute('aria-label', VIEW_TEXTS.install_code_label);
    installCode.setAttribute('autocomplete', 'off');
  }

  const submit = document.createElement('button');
  submit.type = 'submit';
  submit.textContent = VIEW_TEXTS.submit;

  const message = document.createElement('div');
  message.setAttribute('role', 'status');
  message.setAttribute('aria-live', 'polite');

  const form = document.createElement('form');
  form.appendChild(username);
  form.appendChild(password);
  if (installCode !== null) form.appendChild(installCode);
  form.appendChild(submit);

  root.appendChild(form);
  root.appendChild(message);

  let busy = false;
  form.addEventListener('submit', async (event) => {
    if (event && typeof event.preventDefault === 'function') event.preventDefault();
    if (busy) return;
    busy = true;
    submit.disabled = true;
    try {
      const result = await register.run({
        username: username.value,
        typedText: password.value,
        installCode: installCode === null || installCode.value === '' ? undefined : installCode.value,
      });
      if (result.ok === true) {
        message.textContent = VIEW_TEXTS.created;
        password.value = '';
        if (installCode !== null) installCode.value = '';
        if (typeof onRegistered === 'function') onRegistered(result);
        return;
      }
      message.textContent = result.message;
    } finally {
      password.value = '';
      busy = false;
      submit.disabled = false;
    }
  });

  return { form, username, password, installCode, submit, message };
}
