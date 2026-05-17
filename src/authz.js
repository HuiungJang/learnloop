import { ROLE_ORDER } from "./config.js";

export function getUser(db, userId) {
  return db.users.find((user) => user.id === userId && !user.deactivatedAt) ?? null;
}

export function getMemberships(db, userId, organizationId) {
  return db.memberships.filter((membership) => {
    return membership.userId === userId && membership.organizationId === organizationId;
  });
}

export function hasRole(db, userId, organizationId, role, scope = {}) {
  const required = ROLE_ORDER.indexOf(role);
  if (required === -1) return false;
  return getMemberships(db, userId, organizationId).some((membership) => {
    const actual = ROLE_ORDER.indexOf(membership.role);
    if (actual < required) return false;
    if (membership.role === "admin") return true;
    if (scope.projectId && membership.projectId && membership.projectId !== scope.projectId) return false;
    if (scope.teamId && membership.teamId && membership.teamId !== scope.teamId) return false;
    return true;
  });
}

export function assertRole(db, userId, organizationId, role, scope) {
  if (!getUser(db, userId)) {
    const error = new Error("Authentication required");
    error.status = 401;
    throw error;
  }
  if (!hasRole(db, userId, organizationId, role, scope)) {
    const error = new Error("Not allowed for this organization scope");
    error.status = 403;
    throw error;
  }
}

export function canViewPattern(db, userId, card) {
  if (card.publicationStatus === "published" && card.visibility === "organization") {
    return hasRole(db, userId, card.organizationId, "learner", {
      teamId: card.teamId,
      projectId: card.projectId
    });
  }
  return hasRole(db, userId, card.organizationId, "reviewer", {
    teamId: card.teamId,
    projectId: card.projectId
  }) || card.createdByUserId === userId;
}
