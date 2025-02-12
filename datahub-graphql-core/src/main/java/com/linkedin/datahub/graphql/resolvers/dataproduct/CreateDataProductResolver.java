package com.linkedin.datahub.graphql.resolvers.dataproduct;

import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.bindArgument;

import com.datahub.authentication.Authentication;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.exception.AuthorizationException;
import com.linkedin.datahub.graphql.generated.CreateDataProductInput;
import com.linkedin.datahub.graphql.generated.DataProduct;
import com.linkedin.datahub.graphql.types.dataproduct.mappers.DataProductMapper;
import com.linkedin.entity.EntityResponse;
import com.linkedin.metadata.service.DataProductService;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class CreateDataProductResolver implements DataFetcher<CompletableFuture<DataProduct>> {

  private final DataProductService _dataProductService;

  @Override
  public CompletableFuture<DataProduct> get(final DataFetchingEnvironment environment)
      throws Exception {

    final QueryContext context = environment.getContext();
    final CreateDataProductInput input =
        bindArgument(environment.getArgument("input"), CreateDataProductInput.class);
    final Authentication authentication = context.getAuthentication();
    final Urn domainUrn = UrnUtils.getUrn(input.getDomainUrn());

    return CompletableFuture.supplyAsync(
        () -> {
          if (!_dataProductService.verifyEntityExists(domainUrn, context.getAuthentication())) {
            throw new IllegalArgumentException("The Domain provided dos not exist");
          }
          if (!DataProductAuthorizationUtils.isAuthorizedToManageDataProducts(context, domainUrn)) {
            throw new AuthorizationException(
                "Unauthorized to perform this action. Please contact your DataHub administrator.");
          }

          try {
            final Urn dataProductUrn =
                _dataProductService.createDataProduct(
                    input.getProperties().getName(),
                    input.getProperties().getDescription(),
                    authentication);
            _dataProductService.setDomain(
                dataProductUrn, UrnUtils.getUrn(input.getDomainUrn()), authentication);
            EntityResponse response =
                _dataProductService.getDataProductEntityResponse(dataProductUrn, authentication);
            if (response != null) {
              return DataProductMapper.map(response);
            }
            // should never happen
            log.error(String.format("Unable to find data product with urn %s", dataProductUrn));
            return null;
          } catch (Exception e) {
            throw new RuntimeException(
                String.format("Failed to create a new DataProduct from input %s", input), e);
          }
        });
  }
}
