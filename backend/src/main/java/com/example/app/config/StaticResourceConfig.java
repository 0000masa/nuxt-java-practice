package com.example.app.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * static/ に置いた Nuxt の SSG 出力を配信するための設定(フェーズ11)。
 *
 * <p>Spring 標準のリソース配信だけでは、SSG 出力の大半が 404 になる。理由は 2 つ。
 *
 * <ol>
 * <li><b>Nuxt は「ディレクトリ + index.html」形式で出力する。</b> /login のページは
 * static/login/index.html に置かれるが、Spring の ResourceHttpRequestHandler は
 * ディレクトリに index.html を補う機能を持たない(/ だけが welcome-page として特別扱い)。
 * 実測では / 以外の 8 ページすべてが 404 だった
 * <li><b>動的ルートは事前生成されない。</b> /posts/{id} は id が分からないので
 * プリレンダできず、代わりに 200.html(空の SPA 用 HTML)が出力される
 * </ol>
 *
 * <p>そこで解決を 3 段にする。
 *
 * <pre>
 * 1. そのパスのファイルがある?              → 返す      (/_nuxt/xxx.js, /favicon.ico)
 * 2. 拡張子が無く path/index.html がある?    → 返す      (/login → static/login/index.html)
 * 3. 拡張子が無く /api 配下でもない?         → 200.html  (/posts/1)
 *    それ以外                               → 404 のまま
 * </pre>
 *
 * <p>2 を入れているのは、プリレンダ済みの HTML をそのまま配信するため。すべてを 200.html に
 * 落とす実装(404 のエラーページで拾う方式)でも画面は動くが、それだと事前生成した
 * 完成済み HTML が一度も使われず、初回表示が毎回クライアント側の描画待ちになる。
 *
 * <p>3 で /api を除外しているのは、認証済みユーザーが存在しない API を叩いたときに
 * JSON の代わりに HTML を返さないため。未ログインなら Spring Security が先に 401 を返すので
 * ここには来ないが、ログイン中は 404 としてここまで届く。JSON を期待している呼び出し元が
 * パースエラーで落ちる、原因の追いにくいバグになる。
 *
 * <p>このハンドラは "/**" を担当するが、リクエストの探索順では最後に回る。DispatcherServlet は
 * Controller のマッピング(order 0)から順に当て、リソース用のマッピングは最下位に近い順序で
 * 登録されるため。Controller がある URL はここに届かず、届いた時点で「どの Controller にも
 * 一致しなかった」ことが確定している。null を返しても Controller に回るのではなく、404 になる。
 *
 * <p>開発時はこの設定が働く場面がない。static/ の中身が空で(リポジトリでは .gitignore で除外し、
 * docker/app/Dockerfile のビルド時にだけ SSG 出力を COPY する)、3 段すべてが空振りするため。
 * 加えてブラウザの入口は Nuxt dev サーバー(:3000)で、Spring Boot に転送されるのは
 * nuxt.config.ts の devProxy が通す /api だけになる。
 */
@Configuration
// Spring Boot の自動設定も "/**" にリソースハンドラを登録するが、
// 既に同じパターンの登録があれば見送る作りになっている。先に登録されるよう最優先にして、
// どちらが有効になるかが登録順に左右されないようにしている。
// これは起動時に addResourceHandlers を呼ぶ順序であって、リクエストの探索順ではない。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaticResourceConfig implements WebMvcConfigurer {

	/** Nuxt が出力する SPA フォールバック用の HTML。中身は空で、描画は JS が行う。 */
	private static final String SPA_FALLBACK = "200.html";

	/**
	 * Spring Boot が既定で見ている静的リソースの置き場。
	 * 自作の登録に既定値は引き継がれないので、渡さないと locations が空になり全 URL が 404 になる。
	 * このアプリが実際に使うのは static/ だけだが、自動設定の登録を置き換える形になるので、
	 * ライブラリが META-INF/resources に置くリソースを取りこぼさないよう同じ並びを渡す。
	 */
	private static final String[] STATIC_LOCATIONS = {
			"classpath:/META-INF/resources/",
			"classpath:/resources/",
			"classpath:/static/",
			"classpath:/public/"
	};

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**") //あらゆる URL について
				.addResourceLocations(STATIC_LOCATIONS) //この 4 か所から探す
				.resourceChain(true) //解決処理をチェーン(数珠つなぎ)方式にする。true は結果をキャッシュする指定
				.addResolver(new SpaFallbackResolver()); //探し方は、この自作クラスに任せる
	}

	/**
	 * 上の 3 段の解決を行うリゾルバ。
	 *
	 * <p>resourcePath は先頭のスラッシュを除いた相対パスで渡ってくる(/login なら "login")。
	 * location は STATIC_LOCATIONS の各要素で、1 つずつ順に呼ばれる。どこかで非 null を
	 * 返した時点で確定し、すべて null なら 404 になる。
	 */
	private static class SpaFallbackResolver extends PathResourceResolver {

		@Override
		//resourcePath — 探したいファイルの相対パス
		//location — 探しに行く置き場所 71行目のaddResourceLocations(STATIC_LOCATIONS)で設定している
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			// 1. 実ファイルがあればそれを返す
			Resource requested = super.getResource(resourcePath, location);
			if (requested != null) {
				return requested;
			}

			// 拡張子が付いているものはアセットの要求とみなし、無ければ 404 のままにする。
			// ここで HTML に差し替えると、壊れた JS や画像として読み込まれ、
			// 「404 になってほしいのに 200 で意味不明な中身が返る」状態になる。
			if (looksLikeFile(resourcePath)) {
				return null;
			}

			// /api 配下は Controller が持ち場。ここまで来た = 存在しないエンドポイントなので
			// 404 のままにする(HTML を返すと呼び出し元の JSON パースが落ちる)。
			if (resourcePath.startsWith("api/")) {
				return null;
			}

			// 2. ディレクトリ + index.html(プリレンダ済みのページ)
			//trimTrailingCharacter は Spring のユーティリティで、末尾の指定文字を落とします。/login/ と /login のどちらで来ても login に揃う
			String directoryPath = StringUtils.trimTrailingCharacter(resourcePath, '/');
			Resource index = super.getResource(directoryPath + "/index.html", location);
			if (index != null) {
				return index;
			}

			// 3. SPA フォールバック。動的ルート(/posts/1)と、存在しないページの両方がここに来る。
			// 存在しないページも 200 で返るが、その判断は Nuxt 側のエラーページが行う。
			return super.getResource(SPA_FALLBACK, location);
		}

		/** 最後のセグメントに "." を含むかどうかで、ファイル要求かページ要求かを見分ける。trueならファイル要求 */
		private static boolean looksLikeFile(String resourcePath) {
			//lastIndexOf('/') — 最後のスラッシュの位置を探す。見つからなければ -1。
			int lastSlash = resourcePath.lastIndexOf('/');

			//スラッシュが無ければパス全体、あれば最後のスラッシュの次から末尾まで。
			//String.substring() は、文字列の一部を切り出して新しい String を返すメソッドです(Java 標準)。
			// 2つの形
			// str.substring(beginIndex)            // beginIndex から末尾まで
			// str.substring(beginIndex, endIndex)  // beginIndex から endIndex の「手前」まで
			// インデックスは 0 始まりです。ポイントは beginIndex は含む / endIndex は含まない(半開区間)こと。
			String lastSegment = (lastSlash < 0) ? resourcePath : resourcePath.substring(lastSlash + 1);
			return lastSegment.indexOf('.') >= 0;
		}
	}
}
