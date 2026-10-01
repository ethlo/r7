package com.ethlo.r7.undertow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.testcontainers.DockerClientFactory;

/**
 * Skips the annotated test class unless Docker is reachable and the named image is present in the
 * local image store.
 *
 * <p>Some tests run a locally built gateway image ({@code r7-gateway}, produced by
 * {@code ./build.sh}). That image is never published under that name, so Testcontainers
 * would try to pull it and fail the build on any machine, CI runner included, that has not built
 * it. Such tests are opt-in by building the image, not failures by default. The check is
 * deliberately local-only: it never pulls.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(EnabledIfDockerImage.Condition.class)
public @interface EnabledIfDockerImage
{
    /**
     * Image reference as it would be passed to {@code docker image inspect}.
     */
    String value();

    final class Condition implements ExecutionCondition
    {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context)
        {
            final Optional<EnabledIfDockerImage> annotation = AnnotationSupport.findAnnotation(context.getElement(), EnabledIfDockerImage.class);
            if (annotation.isEmpty())
            {
                return ConditionEvaluationResult.enabled("no @EnabledIfDockerImage");
            }

            final String image = annotation.get().value();
            if (!DockerClientFactory.instance().isDockerAvailable())
            {
                return ConditionEvaluationResult.disabled("Docker is not available; required for image " + image);
            }

            try
            {
                DockerClientFactory.instance().client().inspectImageCmd(image).exec();
                return ConditionEvaluationResult.enabled("image " + image + " is present locally");
            }
            catch (final RuntimeException e)
            {
                // NotFoundException for the normal "not built" case; anything else is equally a reason not to run
                return ConditionEvaluationResult.disabled("image " + image + " is not present locally (build it first): " + e.getMessage());
            }
        }
    }
}
