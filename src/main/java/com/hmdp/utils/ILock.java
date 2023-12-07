package com.hmdp.utils;

//实现分布式锁的接口
public interface ILock {

    boolean tryLock(long timeoutSec);

    void unlock();
}
