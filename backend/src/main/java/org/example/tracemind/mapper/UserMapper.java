package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.User;

@Mapper
public interface UserMapper {

    User selectById(@Param("id") String id);

    User selectByEmail(@Param("email") String email);

    int insert(User user);
}
