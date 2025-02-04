package io.metersphere.api.service;

import com.alibaba.fastjson.JSON;
import io.metersphere.api.dto.scenario.request.RequestType;
import io.metersphere.api.jmeter.*;
import io.metersphere.commons.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterVariables;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import sun.security.util.Cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MsResultService {
    // 零时存放实时结果
    private Cache cache = Cache.newHardMemoryCache(0, 3600 * 2);
    public ConcurrentHashMap<String, List<SampleResult>> processCache = new ConcurrentHashMap<>();

    private final static String THREAD_SPLIT = " ";

    private final static String ID_SPLIT = "-";

    public TestResult getResult(String key) {
        if (this.cache.get(key) != null) {
            return (TestResult) this.cache.get(key);
        }
        return null;
    }

    public List<SampleResult> procResult(String key) {
        if (this.processCache.get(key) != null) {
            return this.processCache.get(key);
        }
        return new LinkedList<>();
    }

    public void setCache(String key, SampleResult result) {
        if (key.startsWith("[") && key.endsWith("]")) {
            key = JSON.parseArray(key).get(0).toString();
        }
        List<SampleResult> testResult = this.procResult(key);
        testResult.add(result);
        this.processCache.put(key, testResult);
    }

    public TestResult sysnSampleResult(String key) {
        if (key.startsWith("[") && key.endsWith("]")) {
            key = JSON.parseArray(key).get(0).toString();
        }
        String logs = getJmeterLogger(key, false);
        List<SampleResult> results = this.processCache.get(key);
        boolean isRemove = false;
        TestResult testResult = (TestResult) cache.get(key);
        if (testResult == null) {
            testResult = new TestResult();
        }
        if (CollectionUtils.isNotEmpty(results)) {
            final Map<String, ScenarioResult> scenarios = new LinkedHashMap<>();
            for (SampleResult result : results) {
                if (result.getResponseCode().equals(MsResultCollector.TEST_END)) {
                    testResult.setEnd(true);
                    this.cache.put(key, testResult);
                    isRemove = true;
                    break;
                }
                testResult.setTestId(key);
                if (StringUtils.isNotEmpty(logs)) {
                    testResult.setConsole(logs);
                }
                testResult.setTotal(0);
                this.formatTestResult(testResult, scenarios, result);
            }
            testResult.getScenarios().clear();
            testResult.getScenarios().addAll(scenarios.values());
            testResult.getScenarios().sort(Comparator.comparing(ScenarioResult::getId));
            this.cache.put(key, testResult);

            if (isRemove) {
                this.processCache.remove(key);
            }
        }
        return (TestResult) cache.get(key);
    }

    public void delete(String testId) {
        this.cache.remove(testId);
        MessageCache.reportCache.remove(testId);
    }

    public void formatTestResult(TestResult testResult, Map<String, ScenarioResult> scenarios, SampleResult result) {
        String scenarioName = StringUtils.substringBeforeLast(result.getThreadName(), THREAD_SPLIT);
        String index = StringUtils.substringAfterLast(result.getThreadName(), THREAD_SPLIT);
        String scenarioId = StringUtils.substringBefore(index, ID_SPLIT);
        ScenarioResult scenarioResult;
        if (!scenarios.containsKey(scenarioId)) {
            scenarioResult = new ScenarioResult();
            try {
                scenarioResult.setId(Integer.parseInt(scenarioId));
            } catch (Exception e) {
                scenarioResult.setId(0);
                LogUtil.error("场景ID转换异常: " + e.getMessage());
            }
            scenarioResult.setName(scenarioName);
            scenarios.put(scenarioId, scenarioResult);
        } else {
            scenarioResult = scenarios.get(scenarioId);
        }
        if (result.isSuccessful()) {
            scenarioResult.addSuccess();
            testResult.addSuccess();
        } else {
            scenarioResult.addError(result.getErrorCount());
            testResult.addError(result.getErrorCount());
        }
        RequestResult requestResult = this.getRequestResult(result);
        scenarioResult.getRequestResults().add(requestResult);
        scenarioResult.addResponseTime(result.getTime());

        testResult.addPassAssertions(requestResult.getPassAssertions());
        testResult.addTotalAssertions(requestResult.getTotalAssertions());
        testResult.setTotal(testResult.getTotal() + 1);
        scenarioResult.addPassAssertions(requestResult.getPassAssertions());
        scenarioResult.addTotalAssertions(requestResult.getTotalAssertions());
    }

    public String getJmeterLogger(String testId, boolean removed) {
        Long startTime = FixedTask.tasks.get(testId);
        if (startTime == null) {
            startTime = FixedTask.tasks.get("[" + testId + "]");
        }
        if (startTime == null) {
            startTime = System.currentTimeMillis();
        }
        Long endTime = System.currentTimeMillis();
        Long finalStartTime = startTime;
        String logMessage = JmeterLoggerAppender.logger.entrySet().stream()
                .filter(map -> map.getKey() > finalStartTime && map.getKey() < endTime)
                .map(map -> map.getValue()).collect(Collectors.joining());
        if (removed) {
            if (processCache.get(testId) != null) {
                try {
                    Thread.sleep(2000);
                } catch (Exception e) {
                }
            }
            FixedTask.tasks.remove(testId);
        }
        if (FixedTask.tasks.isEmpty()) {
            JmeterLoggerAppender.logger.clear();
        }
        return logMessage;
    }

    public RequestResult getRequestResult(SampleResult result) {
        RequestResult requestResult = new RequestResult();
        requestResult.setId(result.getSamplerId());
        requestResult.setResourceId(result.getResourceId());
        requestResult.setName(result.getSampleLabel());
        requestResult.setUrl(result.getUrlAsString());
        requestResult.setMethod(getMethod(result));
        requestResult.setBody(result.getSamplerData());
        requestResult.setHeaders(result.getRequestHeaders());
        requestResult.setRequestSize(result.getSentBytes());
        requestResult.setStartTime(result.getStartTime());
        requestResult.setEndTime(result.getEndTime());
        requestResult.setTotalAssertions(result.getAssertionResults().length);
        requestResult.setSuccess(result.isSuccessful());
        requestResult.setError(result.getErrorCount());
        requestResult.setScenario(result.getScenario());
        if (result instanceof HTTPSampleResult) {
            HTTPSampleResult res = (HTTPSampleResult) result;
            requestResult.setCookies(res.getCookies());
        }

        for (SampleResult subResult : result.getSubResults()) {
            requestResult.getSubRequestResults().add(getRequestResult(subResult));
        }
        ResponseResult responseResult = requestResult.getResponseResult();
        responseResult.setBody(result.getResponseDataAsString());
        responseResult.setHeaders(result.getResponseHeaders());
        responseResult.setLatency(result.getLatency());
        responseResult.setResponseCode(result.getResponseCode());
        responseResult.setResponseSize(result.getResponseData().length);
        responseResult.setResponseTime(result.getTime());
        responseResult.setResponseMessage(result.getResponseMessage());
        JMeterVariables variables = JMeterVars.get(result.hashCode());
        if (StringUtils.isNotEmpty(result.getExtVars())) {
            responseResult.setVars(result.getExtVars());
            JMeterVars.remove(result.hashCode());
        } else if (variables != null && CollectionUtils.isNotEmpty(variables.entrySet())) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                builder.append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
            }
            if (StringUtils.isNotEmpty(builder)) {
                responseResult.setVars(builder.toString());
            }
            JMeterVars.remove(result.hashCode());
        }

        for (AssertionResult assertionResult : result.getAssertionResults()) {
            ResponseAssertionResult responseAssertionResult = getResponseAssertionResult(assertionResult);
            if (responseAssertionResult.isPass()) {
                requestResult.addPassAssertions();
            }
            //xpath 提取错误会添加断言错误
            if (StringUtils.isBlank(responseAssertionResult.getMessage()) ||
                    (StringUtils.isNotBlank(responseAssertionResult.getName()) && !responseAssertionResult.getName().endsWith("XPath2Extractor"))) {
                responseResult.getAssertions().add(responseAssertionResult);
            }
        }
        return requestResult;
    }

    private String getMethod(SampleResult result) {
        String body = result.getSamplerData();
        String start = "RPC Protocol: ";
        String end = "://";
        if (StringUtils.contains(body, start)) {
            String protocol = StringUtils.substringBetween(body, start, end);
            if (StringUtils.isNotEmpty(protocol)) {
                return protocol.toUpperCase();
            }
            return RequestType.DUBBO;
        } else if (StringUtils.contains(result.getResponseHeaders(), "url:jdbc")) {
            return "SQL";
        } else {
            String method = StringUtils.substringBefore(body, " ");
            for (HttpMethod value : HttpMethod.values()) {
                if (StringUtils.equals(method, value.name())) {
                    return method;
                }
            }
            return "Request";
        }
    }

    private ResponseAssertionResult getResponseAssertionResult(AssertionResult assertionResult) {
        ResponseAssertionResult responseAssertionResult = new ResponseAssertionResult();
        responseAssertionResult.setName(assertionResult.getName());
        responseAssertionResult.setPass(!assertionResult.isFailure() && !assertionResult.isError());
        if (!responseAssertionResult.isPass()) {
            responseAssertionResult.setMessage(assertionResult.getFailureMessage());
        }
        return responseAssertionResult;
    }
}
