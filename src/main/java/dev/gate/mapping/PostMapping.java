package dev.gate.mapping;

import java.lang.annotation.*;

/** POST リクエストのルートパスを指定するアノテーション */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostMapping {
    String value(); // ルートパス（例: "/echo"）
}
