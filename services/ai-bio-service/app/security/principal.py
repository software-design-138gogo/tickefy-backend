from dataclasses import dataclass
from uuid import UUID

@dataclass(frozen=True)

class CurrentUser:
  user_id: UUID
  email: str | None
  roles: set[str]
  token: str
  claims: dict

  def has_role(self, role: str) -> bool:
    return role in self.roles

  def has_any_role(self, *roles: str) -> bool:
    return any(role in self.roles for role in roles)