/**
 * (C) 2011-2012 Alibaba Group Holding Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 *
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.taobao.common.tedis.group;

import com.taobao.common.tedis.Single;
import com.taobao.common.tedis.atomic.TedisSingle;
import com.taobao.common.tedis.binary.RedisCommands;
import com.taobao.common.tedis.config.HAConfig.ServerProperties;
import com.taobao.common.tedis.config.Router;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 支持MS结构：写master读随机slave; 配置的第一个为master
 *
 * @author juxin.zj E-mail:juxin.zj@taobao.com
 * @since 2012-11-20 下午11:58:09
 * @version 1.0
 */
public final class MSRandomRouter implements Router {

    static final Log logger = LogFactory.getLog(MSRandomRouter.class);
    Random random = new Random();
    private List<ServerProperties> all_props;// 保存最开始加载的配置,里面可能有失效的服务器。
    boolean failover;
    /**
     * 可能在出现异常的时候被修改掉。 然后会使用copy on write 的方式，3s
     * check一次，如果节点回复，就会将它加入列表，也就是会被修改回来。
     */
    volatile RouteData routeData;

    private RouteData allRouteData;// 保存最开始加载的配置,里面可能有失效的服务器。

    private RouteData masterRouteData;
    /**
     * single
     */
    final Map<String, TedisSingle> singleCache = new HashMap<String, TedisSingle>();
    ExecutorService executor_retry = Executors.newSingleThreadExecutor();
    final Retry retry = new Retry();

    public MSRandomRouter(List<ServerProperties> props, boolean failover) {
        this.all_props = props;
        this.failover = failover;
        routeData = createRandomData(props);
        allRouteData = createRandomData(props);
        masterRouteData = createMasterData(props);
        startRetry();
    }

    private RouteData createRandomData(List<ServerProperties> props) {
        int[] weights = new int[props.size()];
        TedisSingle[] group = new TedisSingle[props.size()];
        int prev = 0;
        for (int i = 0; i < props.size(); i++) {
            group[i] = getAtomic(props.get(i));
            weights[i] += prev + props.get(i).readWeight;
            prev = weights[i];
        }

        return new RouteData(props, weights, group);
    }

    private RouteData createMasterData(List<ServerProperties> props) {
        int[] weights = new int[1];
        TedisSingle[] group = new TedisSingle[1];
        group[0] = getAtomic(props.get(0));
        weights[0] += props.get(0).readWeight;
        logger.info("master enabled, master is " + group[0]);
        return new RouteData(props, weights, group);
    }

    // 如果外部出现变更，就把变更带入进来，变更锁，防止重复创建。
    // 出错，从已有路由列表中去除该single.
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public synchronized void onError(Single single) {
        if (!failover) {
            return;
        }
        logger.warn("onError:" + single);

        ServerProperties prop = single.getProperties();
        List<ServerProperties> new_props = (List<ServerProperties>) ((ArrayList) routeData.props).clone();
        new_props.remove(prop);
        routeData = createRandomData(new_props);
        masterRouteData = createMasterData(new_props);

        // 触发重试逻辑
        retry.addRetry(single);
    }

    /**
     * 失败的连接，又重新连上了。 需要在当前使用的列表添加一个。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private synchronized void onReturn(TedisSingle single) {

        logger.warn("onReturn:" + single);

        ServerProperties prop = single.getProperties();
        List<ServerProperties> new_props = (List<ServerProperties>) ((ArrayList) routeData.props).clone();
        if (!new_props.contains(single.getProperties())) {
            new_props.add(prop);
        }
        routeData = createRandomData(new_props);
    }

    private Single route(RouteData routeData) throws Exception {
        int max_random = routeData.weights[routeData.weights.length - 1];
        int x = random.nextInt(max_random);
        for (int i = 0; i < routeData.weights.length; i++) {
            if (x < routeData.weights[i]) {
                return routeData.group[i];
            }
        }
        throw new Exception("routeData is empty");
    }

    @Override
    public Single route() throws Exception {
        return route(routeData);
    }

    @Override
    public RouteData getReadData() {
        return routeData;
    }

    @Override
    public RouteData getWriteData() {
        return masterRouteData;
    }

    @Override
    public RouteData getAllRouteData() {
        return allRouteData;
    }

    synchronized TedisSingle getAtomic(ServerProperties prop) {
        TedisSingle s = singleCache.get(prop.generateKey());
        if (s == null) {
            s = new TedisSingle(prop);
            singleCache.put(prop.generateKey(), s);
        }
        return s;
    }

    @Override
    public Single getAtomic(String key) {
        return singleCache.get(key);
    }

    @Override
    public void destroy() {
        retry.exit = true;
        synchronized (retry) {
            retry.notify();
        }
        executor_retry.shutdownNow();
        synchronized (singleCache) {
            for (Map.Entry<String, TedisSingle> entry : singleCache.entrySet()) {
                entry.getValue().destroy();
            }
        }
    }

    final void startRetry() {
        executor_retry.execute(retry);
    }

    final class Retry implements Runnable {

        volatile boolean exit = false;
        CopyOnWriteArraySet<Single> set = new CopyOnWriteArraySet<Single>();

        public void addRetry(Single single) {
            set.add(single);
            synchronized (this) {
                this.notify();
            }
        }

        @Override
        public void run() {

            while (!exit) {
                for (Single s : set) {
                    logger.warn("retry:" + s);
                    try {
                        synchronized (singleCache) {
                            singleCache.remove(s.getProperties().generateKey()).destroy();
                        }
                    } catch (Exception e) {
                        logger.warn("", e);
                    }
                    TedisSingle ss = null;
                    try {
                        ss = new TedisSingle(s.getProperties());
                        RedisCommands tedis = ss.getTedis();
                        tedis.ping();
                        synchronized (singleCache) {
                            singleCache.put(ss.getProperties().generateKey(), ss);
                        }
                        // success
                        onReturn(ss);
                        set.remove(s);
                    } catch (Throwable t) {
                        s.getErrorCount().incrementAndGet();
                        if (ss != null) {
                            try {
                                ss.destroy();
                            } catch (Exception e) {
                            }
                        }
                        logger.warn("retry throwable : " + s, t);
                    }

                }
                try {
                    synchronized (Retry.this) {
                        wait(1000 * 20);
                    }
                } catch (InterruptedException ex) {
                    logger.warn("Retry Thread InterruptedException", ex);
                }
            }

        }
    }

    @Override
    public String toString() {
        return "RandomRouter{" + "all_props=" + all_props + ", routeData=" + routeData + '}';
    }
}
