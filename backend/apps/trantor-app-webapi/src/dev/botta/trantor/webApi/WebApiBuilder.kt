package dev.botta.trantor.webApi

class WebApiBuilder(appName: String? = null, environmentName: String? = null)
    : BaseWebApiBuilder<WebApi>(appName, environmentName) {

    override fun build() = WebApi(config, services)
}
