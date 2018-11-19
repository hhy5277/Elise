package site.zido.elise.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.zido.elise.*;
import site.zido.elise.downloader.Downloader;
import site.zido.elise.processor.ListenablePageProcessor;
import site.zido.elise.processor.PageProcessor;
import site.zido.elise.select.CompilerException;
import site.zido.elise.select.NumberExpressMatcher;
import site.zido.elise.utils.EventUtils;
import site.zido.elise.utils.UrlUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract Duplicate Removed Scheduler
 *
 * @author zido
 */
public abstract class AbstractScheduler implements TaskScheduler {
    protected Logger LOGGER = LoggerFactory.getLogger(getClass());

    private Set<EventListener> listeners = new HashSet<>();
    private static final byte STATE_PAUSE = 1;
    private static final byte STATE_CANCEL = 2;
    private static final byte STATE_CANCEL_NOW = 3;
    private final Map<Long, Byte> stateMap = new HashMap<>();
    private final Map<Long, Set<Seed>> pauseMap = new ConcurrentHashMap<>();

    @Override
    public void pushRequest(Task task, Request request) {
        Byte state = stateMap.getOrDefault(task.getId(), (byte) -1);
        //will no longer receive new requests when the task is in the canceled state
        if (state >= STATE_CANCEL) {
            return;
        }
        LOGGER.debug("get a candidate url {}", request.getUrl());
        if (shouldReserved(request)
                || noNeedToRemoveDuplicate(request)
                || !getDuplicationProcessor().isDuplicate(task, request)) {
            if (state == STATE_PAUSE) {
                LOGGER.debug(task.getId()+"[" + request.getUrl() + "] received pause");
                addToPauseMap(task.getId(), new Seed(task, request));
                countEvent(state, task);
                return;
            }
            LOGGER.debug("push to queue {}", request.getUrl());
            getCountManager().incr(task, 1);
            Site site = task.getSite();
            if (site.getDomain() == null && request.getUrl() != null) {
                site.setDomain(UrlUtils.getDomain(request.getUrl()));
            }
            pushWhenNoDuplicate(task, request);
        }
    }

    private void addToPauseMap(Long taskId, Seed seed) {
        Set<Seed> seeds = pauseMap.computeIfAbsent(taskId, k -> new HashSet<>());
        seeds.add(seed);
    }

    protected void onProcess(Task task, Request request, Page page) {
        //will no longer process any pages when the task is in the cancel_now state
        final Byte state = stateMap.getOrDefault(task.getId(), (byte) -1);
        if (state == STATE_PAUSE) {
            addToPauseMap(task.getId(), new Seed(task, request, page));
            countEvent(state, task);
            return;
        }
        if (state != STATE_CANCEL_NOW) {
            if (page.isDownloadSuccess()) {
                Site site = task.getSite();
                String codeAccepter = site.getCodeAccepter();
                NumberExpressMatcher matcher;
                try {
                    matcher = new NumberExpressMatcher(codeAccepter);
                } catch (CompilerException e) {
                    throw new RuntimeException(e);
                }
                if (matcher.matches(page.getStatusCode())) {
                    Set<String> links = getProcessor().process(task, page);
                    //will no longer process any pages when the task is in the cancel_now state
                    if (state != STATE_CANCEL) {
                        for (String link : links) {
                            pushRequest(task, new Request(link));
                        }
                    }
                    countEvent(state, task);
                    sleep(site.getSleepTime());
                    return;
                }
            }
        }
        countEvent(state, task);
        if (state == -1) {
            Site site = task.getSite();
            if (site.getCycleRetryTimes() == 0) {
                sleep(site.getSleepTime());
            } else {
                // for cycle retry
                doCycleRetry(task, request);
            }
        }
    }

    private void countEvent(final byte state, Task task) {
        getCountManager().incr(task, -1, i -> {
            if (i == 0) {
                if (state == -1) {
                    EventUtils.notifyListeners(listeners, listener -> listener.onSuccess(task));
                } else if (state > STATE_CANCEL) {
                    EventUtils.notifyListeners(listeners, listener -> listener.onCancel(task));
                } else if (state == STATE_PAUSE) {
                    EventUtils.notifyListeners(listeners, listeners -> listeners.onPause(task));
                }
            }
        });
    }

    protected Page onDownload(Task task, Request request) {
        final Page page = getDownloader().download(task, request);

        if (page.isDownloadSuccess()) {
            EventUtils.mustNotifyListeners(listeners, listener -> listener.onDownloadSuccess(task, request, page));
        } else {
            EventUtils.mustNotifyListeners(listeners, listener -> listener.onDownloadError(task, request, page));
        }
        return page;
    }

    protected void onCancel() {
        EventUtils.mustNotifyListeners(listeners, EventListener::onCancel);
    }

    private void doCycleRetry(Task task, Request request) {
        Object cycleTriedTimesObject = request.getExtra(Request.CYCLE_TRIED_TIMES);
        if (cycleTriedTimesObject == null) {
            pushRequest(task, new Request(request).putExtra(Request.CYCLE_TRIED_TIMES, 1));
        } else {
            int cycleTriedTimes = (Integer) cycleTriedTimesObject;
            cycleTriedTimes++;
            if (cycleTriedTimes < task.getSite().getCycleRetryTimes()) {
                pushRequest(task, new Request(request).putExtra(Request.CYCLE_TRIED_TIMES, cycleTriedTimes));
            }
        }
        sleep(task.getSite().getRetrySleepTime());
    }

    private void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            LOGGER.error("Thread interrupted when sleep", e);
        }
    }


    private boolean shouldReserved(Request request) {
        return request.getExtra(Request.CYCLE_TRIED_TIMES) != null;
    }

    private boolean noNeedToRemoveDuplicate(Request request) {
        return "post".equalsIgnoreCase(request.getMethod());
    }

    /**
     * Specific insert logic implementation,
     * This method is called after removing duplicate data
     *
     * @param task    the task
     * @param request request
     */
    protected abstract void pushWhenNoDuplicate(Task task, Request request);

    @Override
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
        if (getProcessor() instanceof ListenablePageProcessor) {
            ((ListenablePageProcessor) getProcessor()).addEventListener(listener);
        }
    }

    @Override
    public boolean cancel(Task task, boolean giveUpSeeds) {
        final byte newState = giveUpSeeds ? STATE_CANCEL_NOW : STATE_CANCEL;
        synchronized (stateMap) {
            final Byte currentState = stateMap.get(task.getId());
            if (currentState != null) {
                return currentState == newState;
            }
        }
        stateMap.put(task.getId(), newState);
        return true;
    }

    public synchronized boolean pause(Task task) {
        final Byte currentState = stateMap.get(task.getId());
        if (currentState != null) {
            return currentState == STATE_PAUSE;
        }
        stateMap.put(task.getId(), STATE_PAUSE);
        return true;
    }

    public synchronized void recover(Task task) {
        final Byte currentState = stateMap.get(task.getId());
        if (currentState == null) {
            return;
        }
        EventUtils.notifyListeners(listeners, eventListener -> eventListener.onRecover(task));
        stateMap.remove(task.getId());
        Set<Seed> set = pauseMap.getOrDefault(task.getId(), new HashSet<>());
        for (Seed seed : set) {
            if (seed.getPage() != null) {
                onProcess(seed.getTask(), seed.getRequest(), seed.getPage());
            } else {
                pushWhenNoDuplicate(seed.getTask(), seed.getRequest());
            }
        }
    }

    public abstract Downloader getDownloader();

    public abstract PageProcessor getProcessor();

    public abstract CountManager getCountManager();

    public abstract DuplicationProcessor getDuplicationProcessor();
}
