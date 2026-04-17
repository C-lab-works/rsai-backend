package dev.gate.mapping;

import java.lang.annotation.*;

/** GET リクエストのルートパスを指定するアノテーション */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GetMapping {
    String value(); // ルートパス（例: "/hello"）
}
