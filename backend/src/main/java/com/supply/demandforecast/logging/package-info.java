/**
 * Cross-cutting observability: the request-id that ties a single call together across log
 * lines and, via {@link com.supply.demandforecast.config.AppConfig}'s ML transport, across
 * the backend and the Python ML service.
 */
package com.supply.demandforecast.logging;
