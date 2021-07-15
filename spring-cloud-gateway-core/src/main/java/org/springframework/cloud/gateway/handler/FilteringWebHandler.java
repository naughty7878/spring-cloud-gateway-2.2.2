/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * WebHandler that delegates to a chain of {@link GlobalFilter} instances and
 * {@link GatewayFilterFactory} instances then to the target {@link WebHandler}.
 *
 * @author Rossen Stoyanchev
 * @author Spencer Gibb
 * @since 0.1
 */
public class FilteringWebHandler implements WebHandler {

	protected static final Log logger = LogFactory.getLog(FilteringWebHandler.class);

	// 全局过滤器集合
	private final List<GatewayFilter> globalFilters;

	public FilteringWebHandler(List<GlobalFilter> globalFilters) {
		// 加载全局过滤器
		this.globalFilters = loadFilters(globalFilters);
	}

	// 加载全局过滤器
	private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
		return filters.stream().map(filter -> {
			// 创建过滤器适配器
			GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
			// 判断过滤器类型是否是 Ordered
			if (filter instanceof Ordered) {
				int order = ((Ordered) filter).getOrder();
				// 创建顺序网关过滤器
				return new OrderedGatewayFilter(gatewayFilter, order);
			}
			return gatewayFilter;
		}).collect(Collectors.toList());
	}

	/*
	 * TODO: relocate @EventListener(RefreshRoutesEvent.class) void handleRefresh() {
	 * this.combinedFiltersForRoute.clear();
	 */

	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		// 从契约属性中获取路由
		Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
		// 从路由中，获取局部过滤器
		List<GatewayFilter> gatewayFilters = route.getFilters();
		// 复制创建全局过滤器
		List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
		// 合并全局过滤器与局部过滤器
		combined.addAll(gatewayFilters);
		// TODO: needed or cached?
		// 排序
		AnnotationAwareOrderComparator.sort(combined);

		if (logger.isDebugEnabled()) {
			logger.debug("Sorted gatewayFilterFactories: " + combined);
		}

		// 创建默认网关过滤器链
		// 使用网关过滤器链过滤 契约(request + response)
		return new DefaultGatewayFilterChain(combined).filter(exchange);
	}

	private static class DefaultGatewayFilterChain implements GatewayFilterChain {

		private final int index;

		private final List<GatewayFilter> filters;

		DefaultGatewayFilterChain(List<GatewayFilter> filters) {
			this.filters = filters;
			this.index = 0;
		}

		private DefaultGatewayFilterChain(DefaultGatewayFilterChain parent, int index) {
			this.filters = parent.getFilters();
			this.index = index;
		}

		public List<GatewayFilter> getFilters() {
			return filters;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			return Mono.defer(() -> {
				if (this.index < filters.size()) {
					// 根据索引下标，获取过滤器
					GatewayFilter filter = filters.get(this.index);
					// 创建默认网关过滤器链
					DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this,
							this.index + 1);
					// 使用过滤器过滤 契约(request + response)
					return filter.filter(exchange, chain);
				}
				else {
					return Mono.empty(); // complete
				}
			});
		}

	}

	private static class GatewayFilterAdapter implements GatewayFilter {

		private final GlobalFilter delegate;

		GatewayFilterAdapter(GlobalFilter delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			return this.delegate.filter(exchange, chain);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("GatewayFilterAdapter{");
			sb.append("delegate=").append(delegate);
			sb.append('}');
			return sb.toString();
		}

	}

}
