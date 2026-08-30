/**
 * ブラウザが最初に開いた URL のクエリを、Nuxt がルーティングを始める前に控えておく。
 *
 * 使うのはメールのリンクの着地点(/verify-email、/password-reset/confirm)と、
 * バックエンドからのリダイレクトで開かれる /login の ?error=。
 *
 * <b>なぜ useRoute().query ではだめなのか。</b>
 * プリレンダ済みのページを ?token=... 付きで開くと、ページがマウントされる時点では
 * route.query も window.location もクエリを失っている。Nuxt がハイドレーションの食い違いを
 * 避けるため、いったん「プリレンダしたときの URL」(= クエリ無し)へ router.replace してから
 * マウントし、ハイドレーションが終わったあとで本来の URL へ戻すため
 * (nuxt/dist/pages/runtime/plugins/router.js の hasDeferredRoute)。
 * router.replace は history.replaceState を呼ぶので、アドレスバーごと書き換わる。
 * 戻したあとは route.query も正しくなるが、パスが同じなのでページは作り直されず、
 * onMounted は二度と走らない。
 *
 * <b>なぜプラグインなら間に合うのか。</b>
 * クライアントの起動順が applyPlugins → callHook('app:created') → mount だから
 * (nuxt/dist/app/entry.js)。上の差し替えは app:created のフックなので、
 * プラグインの実行時点ではまだ元の URL のままになっている。
 *
 * <b>「最初に開いた URL」であることに注意。</b> アプリ内のページ遷移では変わらない。
 * 普通のクエリ(middleware が付ける ?redirect= など)は useRoute().query を使うこと。
 *
 * 開発サーバー(SSR)ではプリレンダされないので差し替えも起きない。つまり route.query のままでも
 * ローカルでは動いてしまい、SSG でビルドした STG / 本番でだけ壊れる。
 */
export default defineNuxtPlugin(() => {
  const initialQuery = new URL(window.location.href).searchParams

  return {
    provide: {
      /** 最初に開いた URL のクエリを 1 つ読む。無ければ空文字 */
      linkQuery: (name: string) => initialQuery.get(name) ?? '',
    },
  }
})
