package com.example.app.common.queue;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * SQS の受信ループ。専用のスレッド(受信スレッド)を 1 本立て、「受け取る → 処理する → 削除する」を繰り返す。
 *
 * <p>Spring Cloud AWS の {@code @SqsListener} を使わず自分で書いているのは、SQS の仕組みをコードに出すため
 * (→ docs/superpowers/specs/2026-10-11-phase20-sqs-auth-mail-design.md の決定6)。押さえどころは 3 つ。
 * <ol>
 * <li><b>受け取っただけでは消えない。</b>受け取ったメッセージは可視性タイムアウトの間だけ他から見えなくなり、
 *     その間に {@code deleteMessage} しなければ、また配信される</li>
 * <li><b>だから「処理に失敗したら削除しない」だけでリトライになる。</b>回数を数えるのも SQS の仕事で、
 *     上限({@code maxReceiveCount})を超えたら DLQ に移される。このクラスはリトライの仕組みを持たない</li>
 * <li><b>同じメッセージが 2 回届くことがある</b>(少なくとも 1 回の配信)。処理する側が冪等である必要がある
 *     ({@link ProcessedMessage})</li>
 * </ol>
 *
 * <p>{@link SmartLifecycle} にしているのは、アプリの起動しきった後に受信を始め、終了処理の最初に止めるため。
 * ECS がタスクを止めるとき(デプロイ、Spot の中断)に SIGTERM が届くと、Spring のシャットダウンが {@link #stop()} を呼ぶ。
 * 新しい受信をやめ、処理中のメッセージは最後まで終えて削除してから止まる(決定12)。
 * (→ docs/notes/java/spring/smart-lifecycle.md)
 *
 * <p>Spring の Bean として自動登録はしない。使う側がワーカーの役割のときだけ {@code @Bean} で組み立てる。
 */
public class SqsReceiveLoop implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(SqsReceiveLoop.class);

	/** 受信に失敗したとき(SQS に繋がらないなど)、次に試すまで待つ時間 */
	private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

	/**
	 * {@link #stop()} が受信スレッドの終わりを待つ上限。ロングポーリング(最大 20 秒)の途中で止められた場合、
	 * 受信は割り込みでは抜けられないことがある(下の {@link #stop()} のコメント)ので、それより長くする。
	 */
	private static final Duration STOP_WAIT = Duration.ofSeconds(25);

	/** 1 件のメッセージを処理する。例外を投げると「失敗」とみなし、メッセージを削除しない。 */
	// 「このインターフェースは抽象メソッドを 1 つだけ持つ(＝ラムダで実装できる)ように作った関数型インターフェースである」という意図を書いておき、コンパイラに確認させるためのアノテーション
	// **抽象メソッドとは、「名前・引数・戻り値の型だけを決め、中身(処理)を書いていないメソッド」**のこと。何をするかは決まっておらず、「こういう形のメソッドがある」という約束だけを表す
	// 抽象メソッドがちょうど 1 つのインターフェースを「関数型インターフェース」と呼びます。このインターフェースを実装するときは、クラスを書く代わりにラムダ式やメソッド参照を渡せる	
	// このアノテーションが無くてもラムダは使えます。 抽象メソッドが 1 つでさえあれば、Java は関数型インターフェースとして扱う
	@FunctionalInterface
	public interface MessageHandler {
		void handle(String body) throws Exception;
	}

	private final String name;
	private final SqsClient sqsClient;
	private final String queueUrl;
	private final int waitTimeSeconds;
	private final MessageHandler handler;
	private final Runnable onUnexpectedExit;

	// running は受信スレッドと stop() を呼ぶスレッド(シャットダウン)の両方が見るので volatile にする。
	// volatile が無いと、JIT が「ループ内で変わらない値」とみなして読み出しをループの外に出すことがあり、
	// stop() が false を書いても受信スレッドが気づかず止まらないことがある。
	private volatile boolean running;

	// interruptible を守る鍵。enterInterruptible() / leaveInterruptible() / stop() がこれで synchronized する。
	private final Object lock = new Object();

	// 受信スレッドが今「割り込んで起こしてよい待ち」の最中かどうか。stop() はこれが true のときだけ割り込む。
	// 「ループが動いているか」ではない(それは running)。ループが動いていても、処理中は false。
	//   true : enterInterruptible() から leaveInterruptible() までの間。つまり
	//          - SQS からの受信を待っている(ロングポーリング。最大 waitTimeSeconds 秒)
	//          - 受信に失敗した後、再試行まで RETRY_DELAY だけ寝ている
	//   false: それ以外。とくに、メッセージを処理中(handler が DB や SES と通信している)・削除中のとき。
	//          ここで割り込むと、その通信が中途半端に失敗するので、終わるのを待つ
	// 受信スレッドと stop() を呼ぶスレッドの両方が触るので、読み書きは必ず lock の中で行う(だから volatile は要らない)。
	private boolean interruptible;

	private Thread thread;

	/**
	 * @param name             スレッド名とログに使う短い名前
	 * @param waitTimeSeconds  ロングポーリングの秒数(最大 20)。メッセージが無いとき SQS がこの秒数だけ応答を保留する。
	 *                         0 にすると即座に空の応答が返る(ショートポーリング)。空振りの受信も課金されるので、本番は 20
	 * @param onUnexpectedExit 受信スレッドが想定外に終わったときに呼ぶ処理。ワーカーではアプリごと終了させる(決定13)
	 */
	public SqsReceiveLoop(String name, SqsClient sqsClient, String queueUrl, int waitTimeSeconds,
			MessageHandler handler, Runnable onUnexpectedExit) {
		this.name = name;
		this.sqsClient = sqsClient;
		this.queueUrl = queueUrl;
		this.waitTimeSeconds = waitTimeSeconds;
		this.handler = handler;
		this.onUnexpectedExit = onUnexpectedExit;
	}

	@Override
	public void start() {
		running = true;
		thread = new Thread(this::run, "sqs-receiver-" + name);
		thread.start();
		log.info("[{}] 受信を開始しました。queueUrl={}", name, queueUrl);
	}

	/**
	 * 新しい受信をやめ、処理中のメッセージが終わるのを待つ。
	 *
	 * <p>受信の待ち(ロングポーリング)の最中なら割り込みをかけて早く抜けさせる。ただし AWS SDK の同期クライアントは
	 * ソケットの読み取りで止まっていると割り込みでは抜けられないことがあり、その場合は SQS が応答を返すまで
	 * (最大 {@code waitTimeSeconds} 秒)待つことになる。ECS の {@code stopTimeout} はこれより長くしておく。
	 */
	// 【stop() の全体像】
	// 呼ぶのは Spring。アプリを閉じるとき(ECS や docker compose stop の SIGTERM、devtools の再起動など)に呼ばれる。
	// 呼んでいるのはシャットダウンのスレッドで、受信スレッドではない。stop() の中で「待つ」のはシャットダウンのスレッドのほう。
	// やることは 3 段階:
	//   1. running = false で「もう次の受信はしない」と伝える。待ちの最中なら割り込みで起こす
	//   2. 受信スレッドが終わるのを、最大 STOP_WAIT(25 秒)待つ。処理中のメッセージは、ここで最後まで終えてもらう
	//   3. 終わったかどうかをログに出す
	@Override
	public void stop() {
		synchronized (lock) {
			running = false;
			// 受信スレッドが「割り込んでよい待ち」(SQS の応答待ち、または再試行前の sleep)の最中なら、割り込みで起こす。
			// 起こさないと、応答待ちなら最大 20 秒、sleep なら最大 5 秒、止まるのが遅れる。
			// interruptible が false(メッセージを処理中・削除中)なら割り込まない。DB や SES の通信が中途半端に失敗するため。
			// その場合は running = false だけを伝え、下の join で処理が終わるのを待つ。
			// thread != null は、start() が呼ばれる前(受信スレッドがまだ無い)に stop() が呼ばれたときの保険。
			// thread.interrupt() は受信スレッドに「割り込みの印」を付けるだけで、スレッドを強制終了させるものではない
			// (→ leaveInterruptible() の【割り込みの印とは】)。
			if (interruptible && thread != null) {
				thread.interrupt();
			}
		}
		// ここから先は「受信スレッドが終わるのを待つ」部分。待つ必要が無い(待ってはいけない)ときは、ここで返る。
		//
		// 【thread と Thread.currentThread() の違い】
		//   thread(フィールド)       : 受信スレッド。start() の new Thread(this::run, ...) で作って入れたもの。いつ読んでも同じスレッドを指す
		//   Thread.currentThread()   : 今この行を実行しているスレッド = stop() を呼んだスレッド。呼んだのが誰かで変わる
		// stop() は普通、Spring の終了処理のスレッドから呼ばれるので、Thread.currentThread() は終了処理のスレッドになり、
		// thread(受信スレッド)とは別物。比較は false で、下の join に進む。
		// もし stop() を呼んだのが受信スレッド自身なら、両方が同じスレッドを指すので true になる。
		// == は中身ではなく「同じオブジェクトか」を比べる。スレッド 1 本につき Thread オブジェクトは 1 つなので、これで「同じスレッドか」が分かる。
		//
		//   thread == null                    : start() が呼ばれておらず、受信スレッドがそもそも無い。待つ相手がいない
		//   Thread.currentThread() == thread  : stop() を呼んでいるのが受信スレッド自身。自分が終わるのを自分で待つと、永久に待つことになる
		//                                       (受信スレッドが想定外に終わり、後始末でアプリを止めた場合を想定した保険。
		//                                        今の AuthMailQueueConfig#exit() は別スレッドで止めるので、ここには来ないはず)
		if (thread == null || Thread.currentThread() == thread) {
			return;
		}
		try {
			// thread.join(時間) は、「thread(受信スレッド)が終わるまで、今のスレッド(シャットダウンのスレッド)を待たせる」。
			// 終われば、その時点ですぐ戻る。STOP_WAIT(25 秒)たっても終わらなければ、待つのをやめて戻る(例外にはならない)。
			// この間に受信スレッドは、処理中のメッセージを最後まで終えて削除し、while (running) を抜けて終わる。
			// 25 秒にしているのは、SQS の応答待ち(最大 20 秒)が割り込みで抜けられなかった場合でも、待ち切れるようにするため。
			thread.join(STOP_WAIT);
		} catch (InterruptedException e) {
			// join で待っている最中に、シャットダウンのスレッド自身が誰かに割り込まれた(「もう待たずに先へ進め」と言われた)。
			// 待つのはやめて先へ進む。InterruptedException になった時点で割り込みの印は自動で消えているので、
			// Thread.currentThread().interrupt() で、今のスレッド(シャットダウンのスレッド)に印を付け直す。
			// 印を消したままにすると、割り込んだ側の「止まってほしい」というお願いを握りつぶしてしまう。
			// 付け直しておけば、stop() を呼んだ側(Spring)が印を見て、割り込まれたことに気づける。
			// (leaveInterruptible() で印を消してよいのは、受信スレッドでは「止まってほしい」を running で伝えているから。
			//  ここはシャットダウンのスレッドで、印の持ち主は Spring 側なので、勝手に消してはいけない)
			Thread.currentThread().interrupt();
		}
		// thread.isAlive() は、受信スレッドがまだ動いている(終わっていない)なら true。
		// join から戻った理由が「受信スレッドが終わったから」なのか、「25 秒待っても終わらなかった(または割り込まれた)から」なのかを、ここで見分ける。
		//   true  : 終わらなかった。処理中のメッセージがあれば、この後プロセスごと終了して削除されないまま終わる。
		//           可視性タイムアウトが切れれば再配信される(処理する側は、同じメッセージが 2 回届くことに冪等キーで備えている)
		//   false : 終わった。処理中のメッセージは最後まで終えて削除済み
		if (thread.isAlive()) {
			log.warn("[{}] 受信スレッドが {} 秒以内に止まりませんでした。処理中のメッセージは削除されず、再配信されます",
					name, STOP_WAIT.toSeconds());
		} else {
			log.info("[{}] 受信を停止しました", name);
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void run() {
		try {
			// runningがtrueの間 pollOnce()を繰り返す。
			//1 回のリクエストで最大 20 秒待つ形です。メッセージが無い間は、20 秒に 1 回リクエストを送ることになる。
			// SQS はリクエスト単位の課金なので、空振りの回数を減らせて安く済む
			// (0 秒にすると即座に空の返事が返る「ショートポーリング」になり、全速で空振りを繰り返す)
			// 「20 秒に 1 回」は、SQS とは普通に通信できていてメッセージが無いだけ(空振り)の場合。空振りは失敗ではないので sleep は入らず、
			// 空の返事が来たらすぐ次のリクエストを送る。
			// 受信に失敗したとき(SQS に繋がらないなど)だけは別で、再試行前に 5 秒 sleep が入る(→ sleepBeforeRetry())。
			// そのときの間隔は「失敗するまでの時間 + 5 秒」(接続を拒否されてすぐ失敗するなら、ほぼ 5 秒に 1 回)
			while (running) {
				pollOnce();
			}
		} catch (Throwable t) {
			// 処理の失敗(handler の例外)はここまで来ない。来るのは OutOfMemoryError のような想定外のものだけ
			log.error("[{}] 受信スレッドが想定外の例外で終わります", name, t);
		} finally {
			// running が true のまま抜けた = stop() で止められたのではない。
			// プロセスは生きているのに受信していない「ゾンビ」にしないため、後始末を呼ぶ(決定13)
			if (running) {
				running = false;
				log.error("[{}] 受信スレッドが止まりました。後始末を呼びます", name);
				onUnexpectedExit.run();
			}
		}
	}

	private void pollOnce() {
		List<Message> messages = receive();
		// 1件ずつSQSから取得するのに複数来る前提のコードになっているのは
		// SDK の戻り値がもともと Listだから
		for (Message message : messages) {
			if (!running) {
				// pollOnce()が実行されるのはrunningがtrueのときだけ。なのにrunningがfalseのときというのは停止の指示と行き違いで受け取ってしまったということ。
				// 処理せずに返す(可視性タイムアウトを 0 にして、すぐ他に回す)
				release(message);
				continue;
			}
			handle(message);
		}
	}

	private List<Message> receive() {
		// enterInterruptible() が false を返す = 既に stop() が呼ばれている(running が false)。
		// 止まる途中なので SQS には受信しに行かず、「何も受け取らなかった」として空のリストを返す
		if (!enterInterruptible()) {
			//  0 個の空のリストを返す
			return List.of();
		}
		boolean failed = false;
		try {
			return sqsClient.receiveMessage(request -> request
					.queueUrl(queueUrl)
					// 1 件ずつ受け取る。10 件まとめて受け取って順に処理すると、後ろのメッセージは順番を待つ間にも
					// 可視性タイムアウトが進み、処理し終わる前に再配信されうる(決定18)
					.maxNumberOfMessages(1)
					// SQS に受信のリクエストを 1 回送り、SQS 側が最大 20 秒まで返事を保留する
					.waitTimeSeconds(waitTimeSeconds)
					// 何回目の受信かをログに出すために要求する。5 回目で失敗すると DLQ に移る
					.messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
					.messages();
		} catch (SdkException e) {
			if (!running) {
				return List.of(); // 停止のための割り込みで抜けた
			}
			log.warn("[{}] 受信に失敗しました。{} 秒後に再試行します", name, RETRY_DELAY.toSeconds(), e);
			failed = true;
			return List.of();
		} finally {
			leaveInterruptible();
			if (failed) {
				sleepBeforeRetry();
			}
		}
	}

	private void handle(Message message) {
		String receiveCount = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
		try {
			handler.handle(message.body());
		} catch (Exception e) {
			// 削除しない。可視性タイムアウトが切れると SQS が再配信する。これがリトライになる
			log.error("[{}] 処理に失敗しました。削除しないので、可視性タイムアウトの後に再配信されます。messageId={} 受信回数={}",
					name, message.messageId(), receiveCount, e);
			return;
		}
		try {
			sqsClient.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
		} catch (SdkException e) {
			// 処理は終わっているので、再配信されても冪等キーで止まる
			log.warn("[{}] 処理は終わりましたが、削除に失敗しました。再配信されます。messageId={}", name, message.messageId(), e);
		}
	}

	/**
	 * 受け取ったメッセージを、処理も削除もせずに SQS へ返す。停止の指示と行き違いで受け取ってしまったときに
	 * {@link #pollOnce()} から呼ぶ。
	 *
	 * <p>受け取ったメッセージは、可視性タイムアウトの間は他から見えない。何もせずに止まると、タイムアウトが切れるまで
	 * 誰も処理できず、メールが遅れる。そこで可視性タイムアウトを 0 にして、すぐ見える状態に戻し、
	 * 他のワーカー(や入れ替わりで起動したタスク)が受け取れるようにする。
	 *
	 * <p>{@code receiptHandle} は「この受信で受け取ったこのメッセージ」を指す引換券で、受け取った本人だけが持っている。
	 * 削除({@code deleteMessage})や可視性の変更は、メッセージ ID ではなくこれを渡して行う。
	 *
	 * <p>返しても SQS の受信回数({@code ApproximateReceiveCount})は 1 回分増えたままなので、DLQ に移るまでの回数を 1 回使う。
	 */
	private void release(Message message) {
		try {
			sqsClient.changeMessageVisibility(request -> request
					.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(0));
		} catch (SdkException e) {
			// 返せなくても、可視性タイムアウトが切れれば再配信される
			log.warn("[{}] 停止中に受け取ったメッセージを返せませんでした。messageId={}", name, message.messageId(), e);
		}
	}

	/**
	 * 受信に失敗したとき(SQS に繋がらないなど)、次の受信を試すまで {@link #RETRY_DELAY}(5 秒)待つ。
	 * {@link #receive()} の {@code finally} から、失敗したときだけ呼ぶ。空振り(メッセージが無かった)は失敗ではないので待たない。
	 *
	 * <p>待たずにすぐ再試行すると、SQS が落ちている間、失敗する受信を全速で繰り返し、警告ログで埋め尽くしてしまう。
	 *
	 * <p>この待ちは「割り込んでよい待ち」なので、{@link #enterInterruptible()} と {@link #leaveInterruptible()} で挟む。
	 * 待っている間に {@link #stop()} が呼ばれたら、5 秒待ち切らずに割り込みで起こされ、すぐ止まれる。
	 */
	private void sleepBeforeRetry() {
		// enterInterruptible() が false を返す = 既に stop() が呼ばれている(running が false)。
		if (!enterInterruptible()) {
			return;
		}
		try {
			// 今動いているスレッド(受信スレッド)を RETRY_DELAY の間眠らせる。止まるのはこのメソッドだけではなく、
			// このスレッドが実行しているもの全部(呼び出し元の receive() → pollOnce() → run() も、ここから先に進まない)。
			// 他のスレッド(Web のリクエストを処理するスレッドや、stop() を呼ぶシャットダウンのスレッド)は止まらない。
			// 眠っている間に割り込まれると、待ちが中断されて InterruptedException になる
			Thread.sleep(RETRY_DELAY);
		} catch (InterruptedException e) {
			// stop() に起こされた。running は false になっているので、ループがそのまま終わる
		} finally {
			leaveInterruptible();
		}
	}

	/**
	 * 割り込んでよい待ちに入る。既に停止の指示が出ていれば false。
	 *
	 * <p>「割り込んでよい待ち」は次の 2 つ。どちらも呼び出し元が、待ちの前にこのメソッド、後に {@link #leaveInterruptible()} を呼ぶ。
	 * <ul>
	 * <li>SQS からの応答待ち({@link #receive()} の {@code receiveMessage}。ロングポーリングで最大 {@code waitTimeSeconds} 秒。
	 *     メッセージが来ればすぐ終わる)</li>
	 * <li>受信に失敗した後の再試行前の待ち({@link #sleepBeforeRetry()} の {@code Thread.sleep}。{@link #RETRY_DELAY} の 5 秒)</li>
	 * </ul>
	 */
	private boolean enterInterruptible() {
		// synchronized は**関数(メソッド)ではなく、Java の言語に最初から組み込まれているキーワード(予約語)**
		// if や for、class、public と同じ仲間で、変数名やメソッド名には使えない
		// 同じ lock を使っているブロックであれば、別のメソッドにあっても互いに同時には動かない
		synchronized (lock) {
			if (!running) {
				return false;
			}
			// このクラスのフィールドのinterruptibleをtrueにしている
			// this が必要になるのは、引数やローカル変数がフィールドと同じ名前で、フィールドを隠してしまうとき
			interruptible = true;
			return true;
		}
	}

	/**
	 * 割り込んでよい待ち(SQS の応答待ち、または再試行前の sleep。{@link #enterInterruptible()} を参照)から出る。
	 * {@link #enterInterruptible()} と対で、待ちの後の {@code finally} で必ず呼ぶ。
	 * {@code interruptible} を false に戻して以後 {@link #stop()} に割り込ませないようにし、
	 * 行き違いで残った割り込みの印を消してから、次の処理(メッセージの処理・返却)に進ませる。
	 */
	private void leaveInterruptible() {
		synchronized (lock) {
			interruptible = false; // ① もう割り込まないで、と宣言する
			// stop() の割り込みが、待ちを抜けた直後(印を受け止める待ちがもう無いとき)に届くと、割り込みの印だけが残る。
			// ここでの「待ち」は、この直前まで受信スレッドがいた次のどちらか。
			//   - SQS からの応答待ち(receive() の receiveMessage。最大 20 秒。メッセージが来ればすぐ終わる)
			//       例: メッセージが届いて receiveMessage が普通に返った → finally に進む途中で stop() が割り込む
			//   - 再試行前の 5 秒の待ち(sleepBeforeRetry() の Thread.sleep)
			//       例: 5 秒寝終わって Thread.sleep が普通に返った → finally に進む途中で stop() が割り込む
			// 残したまま進むと、次の通信(停止中に受け取ったメッセージを返す release() など)が印で失敗するので、ここで消す。
			// なぜ失敗するのか:
			//   印には「どの待ちのために付けたか」という情報が無い。通信の中(AWS SDK や、その下の HTTP・ソケットの待ち)は
			//   このスレッドに印が付いているのを見ると、「止まってほしいと言われている」と受け取り、待ちに入らずに
			//   その通信を例外で中断する(AWS SDK だと SdkException の一種の AbortedException になることが多い)。
			//   止まるのはスレッドではなく、その通信 1 回。
			// 失敗するとどうなるか:
			//   release() の catch で警告ログが出るだけで、メッセージは返せない。可視性タイムアウトが切れるまで誰も受け取れず、
			//   「すぐ他のワーカーに回す」という release() の目的が果たせない(切れれば再配信されるので、消えはしない)。
			// (印が残って実害が出るのは前者。受け取ったメッセージを release() で返す通信が走るため。
			//  後者はこの後ループを抜けてスレッドが終わるだけだが、同じくここで消える)
			// 止めるかどうかは印ではなく running で判断している
			//
			// 【割り込みの印とは】
			// Java のスレッドは、それぞれ 1 つずつ「割り込み状態(interrupt status)」という true / false の値を持っている。
			// これが割り込みの印。JVM がスレッドごとに管理していて、このクラスのフィールドではない。
			// 割り込みはスレッドを強制終了させるものではなく、「止まってほしい」という印を付けるだけの「お願い」の仕組みで、
			// どう反応するかは割り込まれた側が決める。
			//   - thread.interrupt()  : そのスレッドの印を true にする
			//   - 印が true のまま sleep などの待ちに入る / 待っている最中に印が付く
			//                          : 待ちが InterruptedException で中断され、印は自動で false に戻る
			//   - 待っていない(計算中・待ちを抜けた直後)ときに印が付く
			//                          : 何も起きず、印が残る。次の待ち・通信が始まった瞬間に、それが印に反応して失敗する
			//
			// 【Thread.interrupted() とは】
			// 今動いているスレッド(ここでは受信スレッド)の印を確かめ、同時に false に戻す static メソッド。
			// 戻り値(印が立っていたか)は使わず、「消す」ためだけに呼んでいる。
			// 似た名前の thread.isInterrupted() はインスタンスメソッドで、指定したスレッドの印を見るだけで消さない。
			//
			// 【なぜ消してよいのか】
			// 一般には、印を勝手に消すのは避けるべきとされる。印は「止まってほしい」というお願いそのもので、消すと握りつぶすことになるため
			// (stop() でも join の待ちで消えた印を Thread.currentThread().interrupt() で付け直している)。
			// このクラスで消してよいのは、「止まってほしい」を印ではなく running で伝えているから。
			// 印は「待ちから早く起こす」ためだけに使っており、待ちを出た時点で役目を終えている。消しても止まり損ねることはない。
			Thread.interrupted();  // ② 残っているかもしれない「割り込みの印」を消す
		}
	}
}
