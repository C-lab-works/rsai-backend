package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * アプリ全体で共有する単一の {@link ObjectMapper}。
 *
 * <p>{@code ObjectMapper} はスレッドセーフで、シリアライザ／デシリアライザを内部キャッシュする。
 * クラスごとに個別インスタンスを持つと、各々が独自にキャッシュを温め直すぶんメモリを余分に使い、
 * CPU 面の利点も無い（むしろ共有した方がキャッシュヒット率は上がる）。1 インスタンスを共有する。</p>
 *
 * <p>設定を変えたい箇所が出た場合は {@code MAPPER.copy()} で派生させること
 * （共有インスタンスの設定を実行時に書き換えてはならない）。</p>
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}
}
