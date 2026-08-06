package com.example.app.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.app.user.User;

interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

	/** 引数はハッシュ化済みの値。auth_tokens.token の UNIQUE index で 1 行引く。 */
	Optional<AuthToken> findByToken(String token);

	/** 同じ用途の未使用トークン。新しいトークンを出すときに古い方を無効化するために使う。 */
	List<AuthToken> findByUserAndPurposeAndUsedAtIsNull(User user, AuthTokenPurpose purpose);
}
