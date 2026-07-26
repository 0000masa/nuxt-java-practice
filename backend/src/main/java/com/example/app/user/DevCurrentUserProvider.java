package com.example.app.user;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 認証導入(フェーズ3)までのつなぎ。固定の開発用ユーザーを返す。
 * 初回アクセス時に存在しなければ作成する。
 */
@Component
@Profile("!prod")
public class DevCurrentUserProvider implements CurrentUserProvider {

	static final String DEV_USERNAME = "dev_user";

	private final UserRepository userRepository;

	public DevCurrentUserProvider(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional
	public User getCurrentUser() {
		return userRepository.findByUsername(DEV_USERNAME).orElseGet(() -> {
			User user = new User(DEV_USERNAME, "開発ユーザー", "dev@example.com");
			user.setEmailVerifiedAt(LocalDateTime.now());
			return userRepository.save(user);
		});
	}
}
