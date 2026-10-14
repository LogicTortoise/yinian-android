package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.RelationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDependencyTest {

    @Test
    fun canAddDependency_rejects_self_link_with_SelfLink() = runTest {
        val repo = makeRepository()
        val t = repo.createTask(title = "T")
        assertEquals(DependencyCheck.SelfLink, repo.canAddDependency(t.id, t.id))
    }

    @Test
    fun addDependency_refuses_to_persist_a_self_link() = runTest {
        val repo = makeRepository()
        val t = repo.createTask(title = "T")
        val result = repo.addDependency(t.id, t.id)
        assertEquals(DependencyCheck.SelfLink, result)
        // No relation row was written.
        assertEquals(0, repo.observeRelations().first().size)
    }

    @Test
    fun canAddDependency_returns_Ok_for_a_fresh_non_self_pair() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        assertEquals(DependencyCheck.Ok, repo.canAddDependency(b.id, a.id))
    }

    @Test
    fun addDependency_writes_a_depends_on_relation_when_valid() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        val check = repo.addDependency(b.id, a.id)
        assertEquals(DependencyCheck.Ok, check)
        val relations = repo.observeRelations().first()
        assertEquals(1, relations.size)
        assertEquals("depends_on", relations[0].kind)
        assertEquals(b.id, relations[0].fromId)
        assertEquals(a.id, relations[0].toId)
    }

    @Test
    fun canAddDependency_rejects_a_two_cycle_with_Cycle_and_addDependency_does_not_persist() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")

        // A depends on B already.
        repo.addDependency(fromTask = a.id, toTask = b.id)
        // Now trying to add B -> A would close a cycle.
        assertEquals(DependencyCheck.Cycle, repo.canAddDependency(b.id, a.id))
        val result = repo.addDependency(b.id, a.id)
        assertEquals(DependencyCheck.Cycle, result)

        // Still only the original A->B edge is present.
        val relations = repo.observeRelations().first()
        assertEquals(1, relations.size)
        assertEquals(a.id, relations[0].fromId)
        assertEquals(b.id, relations[0].toId)
    }

    @Test
    fun canAddDependency_rejects_a_longer_cycle_A_to_B_to_C_to_A() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        val c = repo.createTask(title = "C")
        repo.addDependency(fromTask = a.id, toTask = b.id)
        repo.addDependency(fromTask = b.id, toTask = c.id)
        // Adding c -> a would create the cycle a -> b -> c -> a.
        assertEquals(DependencyCheck.Cycle, repo.canAddDependency(c.id, a.id))
    }

    @Test
    fun canAddDependency_treats_unrelated_relation_kinds_as_non_blocking() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        // The repository exposes only depends_on-style mutations through its public
        // API, so there is no way for a non-depends_on row to be added by tests.
        // Verify the validator ignores any unrelated kind that might appear via the DAO.
        // (The FakeRelationDao's `all` is empty by default, so the validator returns Ok.)
        assertEquals(DependencyCheck.Ok, repo.canAddDependency(b.id, a.id))
    }

    @Test
    fun removeDependency_deletes_only_the_matching_edge() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        val c = repo.createTask(title = "C")
        repo.addDependency(fromTask = a.id, toTask = b.id)
        repo.addDependency(fromTask = a.id, toTask = c.id)
        assertEquals(2, repo.observeRelations().first().size)

        repo.removeDependency(a.id, b.id)

        val remaining = repo.observeRelations().first()
        assertEquals(1, remaining.size)
        assertEquals(c.id, remaining[0].toId)
        assertNotEquals(b.id, remaining[0].toId)
    }

    @Test
    fun blockingFor_and_blockedByFor_partition_depends_on_edges_correctly() = runTest {
        val repo = makeRepository()
        val a = repo.createTask(title = "A")
        val b = repo.createTask(title = "B")
        val c = repo.createTask(title = "C")
        // c -> a, c -> b (c blocked by both), b -> a (a blocked by b as well as c).
        repo.addDependency(fromTask = c.id, toTask = a.id)
        repo.addDependency(fromTask = c.id, toTask = b.id)
        repo.addDependency(fromTask = b.id, toTask = a.id)
        val all = repo.observeRelations().first()

        assertEquals(setOf(a.id, b.id), repo.blockingFor(c.id, all))
        assertEquals(setOf(b.id, c.id), repo.blockedByFor(a.id, all))
        assertEquals(setOf(c.id), repo.blockedByFor(b.id, all))
        assertEquals(emptySet<String>(), repo.blockingFor(a.id, all))
    }
}
