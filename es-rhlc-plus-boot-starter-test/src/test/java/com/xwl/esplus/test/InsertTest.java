package com.xwl.esplus.test;

import com.xwl.esplus.test.document.UserDocument;
import com.xwl.esplus.test.document.UserDocument.FullName;
import com.xwl.esplus.test.mapper.UserDocumentMapper;
import org.elasticsearch.geometry.Point;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 插入文档测试
 *
 * @author xwl
 * @since 2022/3/18 10:34
 */
@SpringBootTest
public class InsertTest {
    @Resource
    private UserDocumentMapper userDocumentMapper;

    @Test
    public void testInsert() throws ParseException {
        UserDocument userDocument = new UserDocument();
//        userDocument.setId("1");
        userDocument.setNickname("张三疯");
        userDocument.setFullName(new FullName().setFirstName("张").setLastName("三疯"));
        userDocument.setIdNumber("1001");
        userDocument.setAge(100);
        userDocument.setGender("男");
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        userDocument.setBirthdate(formatter.parse("1922-03-25 00:00:00"));
        userDocument.setCompanyName("四川云恒数联科技有限公司");
        userDocument.setCompanyAddress("成都市武侯区天府二街151号");
        userDocument.setCompanyLocation("30.584736,104.074091");
        userDocument.setRemark("软件开发");
        userDocument.setCreatedTime(LocalDateTime.now());
        userDocumentMapper.insert(userDocument);
    }

    @Test
    public void testInsertBatch() throws ParseException {
        List<UserDocument> list = new ArrayList<>();
        for (int i = 100; i < 200; i++) {
            UserDocument userDocument = new UserDocument();
            userDocument.setNickname("张三疯");
            userDocument.setFullName(new FullName().setFirstName("张").setLastName("三疯"));
            userDocument.setIdNumber(String.valueOf(i));
            userDocument.setAge(i);
            userDocument.setGender("男");
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
            userDocument.setBirthdate(formatter.parse("1922-03-25 00:00:00"));
            userDocument.setCompanyName("四川云恒数联科技有限公司");
            userDocument.setCompanyAddress("成都市武侯区天府二街151号");
            userDocument.setCompanyLocation("30.584736,104.074091");
            userDocument.setRemark("软件开发");
            userDocument.setCreatedTime(LocalDateTime.now());
            Point point = new Point(10, 12);
            userDocument.setGeoLocation(point.toString());
            list.add(userDocument);
        }
        userDocumentMapper.insertBatch(list);
    }
}