package dev.gate.core;

import org.slf4j.LoggerFactory;

/**
 * SLF4J の薄いラッパー。isXxxEnabled() ガードによってメッセージのフォーマット処理と
 * SLF4J 内部へのフォワードをスキップする。
 * varargs (Object[]) の配列自体は呼び出しサイトで生成されるためガードでは防げないが、
 * SLF4J も内部でレベルチェックを行うため、このガードは主にフォーマット文字列の連結コスト削減が目的。
 * ホットパスではデフォルト INFO レベルなので、debug() のオーバーヘッドがリクごとに乗ってくるのを防ぐ。
 */
public class Logger {
    private final org.slf4j.Logger logger;

    public Logger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void info(String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.info(format, args);
        }
    }

    public void warn(String message) {
        logger.warn(message);
    }

    public void warn(String format, Object... args) {
        if (logger.isWarnEnabled()) {
            logger.warn(format, args);
        }
    }

    public void error(String message) {
        logger.error(message);
    }

    public void error(String message, Throwable e) {
        logger.error(message, e);
    }

    public void error(String format, Object... args) {
        if (logger.isErrorEnabled()) {
            logger.error(format, args);
        }
    }

    public void debug(String message) {
        logger.debug(message);
    }

    public void debug(String format, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.debug(format, args);
        }
    }
}