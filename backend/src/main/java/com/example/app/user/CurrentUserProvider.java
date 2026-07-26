package com.example.app.user;

/**
 * 「いまリクエストしているユーザー」を返す抽象。
 * フェーズ3(認証)でセッションベースの実装に置き換える。
 * それまでは開発用ユーザーを返す {@link DevCurrentUserProvider} が使われる。
 */
public interface CurrentUserProvider {

	User getCurrentUser();
}
