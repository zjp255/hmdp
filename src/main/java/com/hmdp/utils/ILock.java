package com.hmdp.utils;

/**
 * @author ZhuJinPeng
 * @version 1.0
 */
public interface ILock {

    /**
     * 获取锁
     * @param timeout
     * @return
     */
    boolean tryLock(long timeout);

    /**
     * 释放锁
     */
    void unlock();
}
