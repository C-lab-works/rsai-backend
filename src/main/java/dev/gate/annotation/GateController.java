package dev.gate.annotation;

import java.lang.annotation.*;

/** Gate フレームワークのコントローラークラスを示すマーカーアノテーション */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GateController {
}
