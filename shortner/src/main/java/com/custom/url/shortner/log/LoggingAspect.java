package com.custom.url.shortner.log;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class LoggingAspect {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);

    @Around("@annotation(LogEntryExit)")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        Object[] args = joinPoint.getArgs();

        // Log entry
        logger.info("Enter--> {}.{}() with arguments: {}", className, methodName, Arrays.toString(args));

        long start = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed(); // Executes the actual method
        } catch (Throwable throwable) {
            logger.error("Exception in {}.{}(): {}", className, methodName, throwable.getMessage());
            throw throwable;
        }
        long executionTime = System.currentTimeMillis() - start;

        // Log exit
        logger.info("Exit<-- {}.{}() with result: {} | Execution time: {}ms",
                className, methodName, result, executionTime);

        return result;
    }
}