package com.example.app.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * ログイン時に「このメールアドレスのユーザーは誰か」を Spring Security へ渡す役。
 *
 * <p>パスワードの照合はここではなく DaoAuthenticationProvider が行う。このクラスの仕事は
 * ユーザーを 1 件返すことだけで、返した {@link AppUserDetails} の {@code getPassword()} と
 * 入力値を照合するのはフレームワーク側。
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public AppUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new UsernameNotFoundException("メールアドレスが未登録: " + email));

		// パスワード未設定のユーザー(Google ログインのみのユーザーと dev_user)はパスワードログインできない。
		//
		// ここで UsernameNotFoundException を投げるのが正しい対処で、自分で対策を書く必要はない。
		// DaoAuthenticationProvider が 2 つのことを自動でやってくれる:
		//   1. hideUserNotFoundExceptions(既定 true)により BadCredentialsException に差し替え、
		//      「未登録」と「パスワード違い」でメッセージが変わらないようにする
		//   2. ユーザーが見つからなかった場合もダミーのハッシュとの照合を走らせ、
		//      応答時間の差からアカウントの存在を推測されないようにする(タイミング攻撃対策)
		if (user.getPasswordHash() == null) {
			throw new UsernameNotFoundException("パスワード未設定のユーザー: " + email);
		}
		return AppUserDetails.from(user);
	}
}
