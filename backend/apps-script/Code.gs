/**
 * Unscroll feedback mail receiver.
 *
 * Deploy this as an Apps Script Web App under the Gmail account that should
 * receive feedback. The Android app posts JSON to the generated /exec URL.
 * Keep the token in Script Properties, never in this file.
 */
const MAX_MESSAGE_LENGTH = 2000;

function doPost(event) {
  try {
    const payload = JSON.parse(event.postData && event.postData.contents || '{}');
    const expectedToken = PropertiesService.getScriptProperties()
      .getProperty('UNSCROLL_FEEDBACK_TOKEN');
    const recipient = PropertiesService.getScriptProperties()
      .getProperty('UNSCROLL_FEEDBACK_RECIPIENT');

    if (!expectedToken || payload.token !== expectedToken) {
      return json({ ok: false, error: 'unauthorized' });
    }
    if (!recipient) {
      return json({ ok: false, error: 'receiver_not_configured' });
    }

    const type = clean(payload.type, 24) || 'Feedback';
    const username = clean(payload.username, 30) || 'anonymous';
    const appVersion = clean(payload.appVersion, 20) || 'unknown';
    const androidVersion = clean(payload.androidVersion, 20) || 'unknown';
    const message = clean(payload.message, MAX_MESSAGE_LENGTH);

    if (!message) {
      return json({ ok: false, error: 'message_required' });
    }

    // Simple per-user cooldown protects a small beta inbox from accidental
    // repeat taps. It is intentionally not user-facing authentication.
    const cooldownKey = `feedback:${username.toLowerCase()}`;
    const cache = CacheService.getScriptCache();
    if (cache.get(cooldownKey)) {
      return json({ ok: false, error: 'try_again_later' });
    }
    cache.put(cooldownKey, '1', 60);

    const subject = `[Unscroll · ${type}] @${username}`;
    const body = [
      'New Unscroll feedback',
      '',
      message,
      '',
      '—',
      `From: @${username}`,
      `App version: ${appVersion}`,
      `Android: ${androidVersion}`,
      `Received: ${new Date().toISOString()}`
    ].join('\n');

    MailApp.sendEmail({
      to: recipient,
      subject: subject,
      body: body,
      name: 'Unscroll feedback'
    });
    return json({ ok: true });
  } catch (error) {
    console.error(error);
    return json({ ok: false, error: 'server_error' });
  }
}

function clean(value, maxLength) {
  return String(value || '')
    .replace(/[\u0000-\u001F\u007F]/g, ' ')
    .trim()
    .slice(0, maxLength);
}

function json(value) {
  return ContentService
    .createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
