// The only JavaScript we own. htmx does the requests and swaps; this file covers what the server can't render
// because no usable response arrived: a network failure, the 10s client timeout, or a status htmx was configured
// not to swap (see responseHandling in index.html). It also mirrors the loading state to assistive technology.
(function () {
  'use strict';

  var MESSAGES = {
    'htmx:sendError': "We couldn't reach the server. Check your connection and try again.",
    'htmx:timeout': 'This is taking longer than expected. Please try again.',
    'htmx:responseError': 'Something went wrong on our side. Please try again.'
  };

  function showError(message) {
    var outcome = document.getElementById('quote-outcome');
    if (!outcome) {
      return;
    }
    var alert = document.createElement('div');
    alert.className = 'alert';
    alert.setAttribute('role', 'alert');
    alert.tabIndex = -1;
    alert.textContent = message; // textContent, never innerHTML
    outcome.replaceChildren(alert);
    alert.focus();
  }

  function setBusy(event, busy) {
    var button = event.target.querySelector && event.target.querySelector('button[type=submit]');
    if (button) {
      button.setAttribute('aria-busy', String(busy));
    }
  }

  Object.keys(MESSAGES).forEach(function (eventName) {
    document.addEventListener(eventName, function () { showError(MESSAGES[eventName]); });
  });
  document.addEventListener('htmx:beforeRequest', function (event) { setBusy(event, true); });
  document.addEventListener('htmx:afterRequest', function (event) { setBusy(event, false); });
})();
