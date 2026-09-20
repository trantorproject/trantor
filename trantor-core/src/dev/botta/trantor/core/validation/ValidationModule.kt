package dev.botta.trantor.core.validation

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import jakarta.validation.*
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator

class ValidationModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        // IfMissing, so an application that wants another provider or message interpolator keeps it
        services.addSingletonIfMissing<ValidatorFactory> {
            Validation.byProvider(HibernateValidator::class.java)
                .configure()
                .messageInterpolator(ParameterMessageInterpolator())
                .buildValidatorFactory()
        }
        services.addSingletonIfMissing<Validator> { it.get<ValidatorFactory>().validator }
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
