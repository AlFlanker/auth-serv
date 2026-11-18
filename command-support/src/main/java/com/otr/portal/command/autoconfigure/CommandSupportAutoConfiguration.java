package com.otr.portal.command.autoconfigure;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Автоконфигурация компонентов модуля command-support.
 */
@AutoConfiguration
@Import({
        ResilienceRegistryConfig.class,
        CommandConfigRegistry.class,
        CommandResilienceDecorator.class
})
public class CommandSupportAutoConfiguration {
}
