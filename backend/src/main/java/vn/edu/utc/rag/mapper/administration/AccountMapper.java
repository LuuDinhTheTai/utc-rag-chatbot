package vn.edu.utc.rag.mapper.administration;

import org.springframework.stereotype.Component;
import vn.edu.utc.rag.dto.administration.AccountResponse;
import vn.edu.utc.rag.dto.common.IdResponse;
import vn.edu.utc.rag.persistence.entity.*;

@Component
public class AccountMapper {
  public AccountResponse account(UsersEntity user) {
    return new AccountResponse(
        user.getId(), user.getEmail(), user.getStatus(), user.getAuthEpoch(), user.getCreatedAt());
  }

  public IdResponse roleId(RoleAssignmentsEntity role) {
    return new IdResponse(role.getId());
  }
}
