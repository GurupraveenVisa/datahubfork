package com.linkedin.metadata.entity;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.identity.CorpUserInfo;
import com.linkedin.metadata.AspectGenerationUtils;
import com.linkedin.metadata.AspectIngestionUtils;
import com.linkedin.metadata.CassandraTestUtils;
import com.linkedin.metadata.config.PreProcessHooks;
import com.linkedin.metadata.entity.cassandra.CassandraAspectDao;
import com.linkedin.metadata.entity.cassandra.CassandraRetentionService;
import com.linkedin.metadata.event.EventProducer;
import com.linkedin.metadata.key.CorpUserKey;
import com.linkedin.metadata.models.registry.EntityRegistryException;
import com.linkedin.metadata.query.ExtraInfo;
import com.linkedin.metadata.query.ListUrnsResult;
import com.linkedin.metadata.service.UpdateIndicesService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.testcontainers.containers.CassandraContainer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * A class that knows how to configure {@link EntityServiceTest} to run integration tests against a
 * Cassandra database.
 *
 * <p>This class also contains all the test methods where realities of an underlying storage leak
 * into the {@link EntityServiceImpl} in the form of subtle behavior differences. Ideally that
 * should never happen, and it'd be great to address captured differences.
 */
public class CassandraEntityServiceTest
    extends EntityServiceTest<CassandraAspectDao, CassandraRetentionService> {

  private CassandraContainer _cassandraContainer;

  public CassandraEntityServiceTest() throws EntityRegistryException {}

  @BeforeClass
  public void setupContainer() {
    _cassandraContainer = CassandraTestUtils.setupContainer();
  }

  @AfterClass
  public void tearDown() {
    _cassandraContainer.stop();
  }

  @BeforeMethod
  public void setupTest() {
    CassandraTestUtils.purgeData(_cassandraContainer);
    configureComponents();
  }

  private void configureComponents() {
    CqlSession session = CassandraTestUtils.createTestSession(_cassandraContainer);
    _aspectDao = new CassandraAspectDao(session);
    _aspectDao.setConnectionValidated(true);
    _mockProducer = mock(EventProducer.class);
    _mockUpdateIndicesService = mock(UpdateIndicesService.class);
    PreProcessHooks preProcessHooks = new PreProcessHooks();
    preProcessHooks.setUiEnabled(true);
    _entityServiceImpl =
        new EntityServiceImpl(
            _aspectDao,
            _mockProducer,
            _testEntityRegistry,
            true,
            _mockUpdateIndicesService,
            preProcessHooks);
    _retentionService = new CassandraRetentionService(_entityServiceImpl, session, 1000);
    _entityServiceImpl.setRetentionService(_retentionService);
  }

  /**
   * Ideally, all tests would be in the base class, so they're reused between all implementations.
   * When that's the case - test runner will ignore this class (and its base!) so we keep this dummy
   * test to make sure this class will always be discovered.
   */
  @Test
  public void obligatoryTest() throws AssertionError {
    Assert.assertTrue(true);
  }

  @Override
  @Test
  public void testIngestListLatestAspects() throws AssertionError {

    // TODO: If you're modifying this test - match your changes in sibling implementations.

    // TODO: Move this test into the base class,
    //  If you can find a way for Cassandra and relational databases to share result ordering rules.

    final int totalEntities = 100;
    final int pageSize = 30;
    final int expectedTotalPages = 4;
    final int expectedEntitiesInLastPage = 10;

    Map<Urn, CorpUserInfo> writtenAspects =
        AspectIngestionUtils.ingestCorpUserInfoAspects(_entityServiceImpl, totalEntities);
    Set<Urn> writtenUrns = writtenAspects.keySet();
    String entity = writtenUrns.stream().findFirst().get().getEntityType();
    String aspect = AspectGenerationUtils.getAspectName(new CorpUserInfo());

    List<Urn> readUrns = new ArrayList<>();
    for (int pageNo = 0; pageNo < expectedTotalPages; pageNo++) {
      boolean isLastPage = pageNo == expectedTotalPages - 1;
      int pageStart = pageNo * pageSize;
      int expectedEntityCount = isLastPage ? expectedEntitiesInLastPage : pageSize;
      int expectedNextStart = isLastPage ? -1 : pageStart + pageSize;

      ListResult<RecordTemplate> page =
          _entityServiceImpl.listLatestAspects(entity, aspect, pageStart, pageSize);

      // Check paging metadata works as expected
      assertEquals(page.getNextStart(), expectedNextStart);
      assertEquals(page.getPageSize(), pageSize);
      assertEquals(page.getTotalCount(), totalEntities);
      assertEquals(page.getTotalPageCount(), expectedTotalPages);
      assertEquals(page.getValues().size(), expectedEntityCount);

      // Remember all URNs we've seen returned for later assertions
      readUrns.addAll(
          page.getMetadata().getExtraInfos().stream()
              .map(ExtraInfo::getUrn)
              .collect(Collectors.toList()));
    }
    assertEquals(readUrns.size(), writtenUrns.size());

    // Check that all URNs we've created were seen in some page or other (also check that none were
    // seen more than once)
    // We can't be strict on exact order of items in the responses because Cassandra query
    // limitations get in the way here.
    for (Urn wUrn : writtenUrns) {
      long matchingUrnCount =
          readUrns.stream().filter(rUrn -> rUrn.toString().equals(wUrn.toString())).count();
      assertEquals(
          matchingUrnCount,
          1L,
          String.format(
              "Each URN should appear exactly once. %s appeared %d times.",
              wUrn, matchingUrnCount));
    }
  }

  @Override
  @Test
  public void testIngestListUrns() throws AssertionError {

    // TODO: If you're modifying this test - match your changes in sibling implementations.

    // TODO: Move this test into the base class,
    //  If you can find a way for Cassandra and relational databases to share result ordering rules.

    final int totalEntities = 100;
    final int pageSize = 30;
    final int expectedTotalPages = 4;
    final int expectedEntitiesInLastPage = 10;

    Map<Urn, CorpUserKey> writtenAspects =
        AspectIngestionUtils.ingestCorpUserKeyAspects(_entityServiceImpl, totalEntities);
    Set<Urn> writtenUrns = writtenAspects.keySet();
    String entity = writtenUrns.stream().findFirst().get().getEntityType();

    List<Urn> readUrns = new ArrayList<>();
    for (int pageNo = 0; pageNo < expectedTotalPages; pageNo++) {
      boolean isLastPage = pageNo == expectedTotalPages - 1;
      int pageStart = pageNo * pageSize;
      int expectedEntityCount = isLastPage ? expectedEntitiesInLastPage : pageSize;

      ListUrnsResult page = _entityServiceImpl.listUrns(entity, pageStart, pageSize);

      // Check paging metadata works as expected
      assertEquals(page.getStart().intValue(), pageStart);
      assertEquals(page.getTotal().intValue(), totalEntities);
      assertEquals(page.getEntities().size(), expectedEntityCount);

      // Remember all URNs we've seen returned for later assertions
      readUrns.addAll(page.getEntities());
    }
    assertEquals(readUrns.size(), writtenUrns.size());

    // Check that all URNs we've created were seen in some page or other (also check that none were
    // seen more than once)
    // We can't be strict on exact order of items in the responses because Cassandra query
    // limitations get in the way here.
    for (Urn wUrn : writtenUrns) {
      long matchingUrnCount =
          readUrns.stream().filter(rUrn -> rUrn.toString().equals(wUrn.toString())).count();
      assertEquals(
          matchingUrnCount,
          1L,
          String.format(
              "Each URN should appear exactly once. %s appeared %d times.",
              wUrn, matchingUrnCount));
    }
  }

  @Override
  @Test
  public void testNestedTransactions() {
    // Doesn't look like Cassandra can support nested transactions (or nested batching).
  }
}
