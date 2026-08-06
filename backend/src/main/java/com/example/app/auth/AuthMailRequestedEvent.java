package com.example.app.auth;

/**
 * 「認証メールを送る必要が生じた」ことを表すイベント。
 *
 * <p>AuthService はトークンを発行したあとこれを発行するだけで、実際の送信は
 * {@link AuthMailSender} がトランザクションのコミット後に行う。こう分けている理由は
 * DB トランザクションを SMTP の往復時間だけ開けたままにしないため(設計の決定7)。
 *
 * @param toEmail  宛先
 * @param purpose  用途。メールの件名・本文・リンク先のパスがこれで決まる
 * @param rawToken URL に載せる<b>生の</b>トークン。DB にはハッシュしか無いので、
 *                 送信に必要な生の値はこのイベントで運ぶしかない
 */
record AuthMailRequestedEvent(String toEmail, AuthTokenPurpose purpose, String rawToken) {
}
