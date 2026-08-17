package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * Google ログインで作るユーザーの username 生成規則の検証。
 *
 * <p>衝突の回避が DB を見ないと決まらないので、純粋な単体テストではなく実 DB を使う。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UsernameGenerator.class)
class UsernameGeneratorTest {

	@Autowired
	UsernameGenerator usernameGenerator;

	@Autowired
	UserRepository userRepository;

	@Test
	@DisplayName("ローカル部を小文字化し、使えない文字を _ にして 20 文字までに切る")
	void normalizesLocalPart() {
		assertThat(usernameGenerator.generateFrom("Masanori.Adachi@gmail.com")).isEqualTo("masanori_adachi");
		// プラス記号付きのアドレスも、前後に _ を残さずに均される
		assertThat(usernameGenerator.generateFrom("taro+news@example.com")).isEqualTo("taro_news");
		// 21 文字以上のローカル部は 20 文字で切られる
		assertThat(usernameGenerator.generateFrom("abcdefghijklmnopqrstuvwxyz@example.com"))
				.isEqualTo("abcdefghijklmnopqrst");
	}

	@Test
	@DisplayName("既に使われていたら末尾に連番を振る")
	void avoidsCollision() {
		userRepository.saveAndFlush(new User("taken", "先客", "taken-1@example.com"));
		assertThat(usernameGenerator.generateFrom("taken@example.com")).isEqualTo("taken_2");

		userRepository.saveAndFlush(new User("taken_2", "先客2", "taken-2@example.com"));
		assertThat(usernameGenerator.generateFrom("taken@example.com")).isEqualTo("taken_3");
	}

	@Test
	@DisplayName("使える文字が 1 つも無いローカル部でも username を返す")
	void fallsBackWhenNothingUsable() {
		// username は NOT NULL UNIQUE なので、空文字を返すと一意制約に当たり続けて復帰できない。
		assertThat(usernameGenerator.generateFrom("!!!@example.com")).isEqualTo("user");
	}
}
