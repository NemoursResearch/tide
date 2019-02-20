/*
 * Copyright 2019 The Board of Trustees of The Leland Stanford Junior University.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.github.susom.starr.deid.anonymizers;

import com.github.susom.database.DatabaseException;
import com.github.susom.database.DatabaseProvider;
import com.github.susom.starr.Utility;

import edu.stanford.irt.core.facade.AnonymizedItem;
import edu.stanford.irt.core.facade.Anonymizer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Quintet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Name identification and surrogate.
 * @author wenchengl
 */

public class NameSurrogate implements Anonymizer {

  private static final Logger log = LoggerFactory.getLogger(NameSurrogate.class);
  private static final String defaultReplacementWord = "[REMOVED]";

  private static final AtomicBoolean dbLoaded = new AtomicBoolean(false);
  private static final int minFirstNamePopularity = 20;
  private static final int minLastNamePopularity = 500;

  String inProcessDbUrl = "jdbc:hsqldb:mem:data";
  DatabaseProvider.Builder inProcessDbBuilder = null;

  private static final String searchNameQuery
      = "select name, sex from us_firstname where name = ? limit 1";
  private static final String findFirstNameRangeBySexI12Query
      = "select range from us_firstname_range "
          + "where sex = ? and name_i1 = ? and name_i2 = ?  limit 1";
  private static final String findFirstNameBySexI12SeqQuery
      = "select name,sex from us_firstname "
          + "where sex = ? and name_i1 = ? and name_i2 = ? and seq = ? limit 1";


  private static final String findLastNameRangeByI12Query
      = "select range from us_lastname_range where name_i1 = ? and name_i2 = ?  limit 1";
  private static final String findLastNameByI12SeqQuery
      = "select name from us_lastname where name_i1 = ? and name_i2 = ? and seq = ? limit 1";

  private Random random = new Random(UUID.randomUUID().hashCode());

  private String[] names;
  private String[] replacements;
  private String anonymizerType;

  private List<AnonymizedItem> knownNameItems;
  private String zipCode;
  private String gender;
  private Date dob;

  private NameDictionay[] dic;

  public enum NameDictionay {
    Firstname, Lastname
  }

  public static final List<Quintet<String,String, String, String,String>>
      hsqlTables = new ArrayList<>();

  static {

    //firstname with three char index : '82,79,76,59,F,Rolonda,48'
    hsqlTables.add(Quintet.with(
        "us_firstname",
        "name_i1,name_i2,name_i3,seq,sex,name,occurrences",
        "sex,name_i1,name_i2,seq",
        "create table us_firstname "
              + "(name_i1 integer,name_i2 integer,name_i3 integer,seq integer, sex varchar(20),"
              + " name varchar(80), occurrences integer)",
        "firstname_g3.csv"));

    //firstname range for each group (i1,i2)  : '65,65,M,43'
    hsqlTables.add(Quintet.with(
        "us_firstname_range","name_i1,name_i2,sex,range","sex,name_i1,name_i2",
        "create table us_firstname_range "
              + "(name_i1 integer,name_i2 integer, sex varchar(20), range integer)",
        "firstname_g_range.csv"));


    //lastname with three char index
    //NAME_I1,NAME_I2,NAME_I3,SEQ,NAME,OCCURRENCES: '76,65,70,2048,LAFANS,1'
    hsqlTables.add(Quintet.with(
        "us_lastname",
        "name_i1,name_i2,name_i3,seq,name,occurrences",
        "name_i1,name_i2,seq",
        "create table us_lastname "
              + "(name_i1 integer,name_i2 integer,name_i3 integer,"
                  + "seq integer,name varchar(80),occurrences integer)",
        "lastname_g3.csv"));
    //lastname range for each group (i1,i2)  : '65,65,43'
    hsqlTables.add(Quintet.with(
        "us_lastname_range","name_i1,name_i2,range","name_i1,name_i2",
        "create table us_lastname_range (name_i1 integer,name_i2 integer, range integer)",
        "lastname_g_range.csv"));

  }

  /**
   * constructor.
   * @param names array of input names.
   * @param dictionary is Firstname or Lastname for each element
   */
  public NameSurrogate(String[] names, String anonymizerType, NameDictionay[] dictionary)
        throws SQLException {
    this.anonymizerType = anonymizerType;
    this.names = names;
    this.dic = dictionary;

    synchronized (dbLoaded) {
      if (!dbLoaded.get()) {

        for (Quintet<String, String, String,String,String> tableDef : hsqlTables) {
          Utility.loadHsqlTable(tableDef);
        }

        dbLoaded.set(true);
      }
    }

    inProcessDbBuilder = DatabaseProvider.fromDriverManager(inProcessDbUrl);

  }


  @Override
  public String scrub() {
    return null;
  }

  @Override
  public String scrub(String text, List<AnonymizedItem> list) {

    getNameSurrogate();

    String out = text;

    for (int i = 0; names != null && i < names.length; i++) {
      // Ignore ones has shorter than 3 character length
      if ((names[i] == null) || (names[i].trim().length() <= 2)) {
        continue;
      }
      Matcher r = Pattern.compile("\\b(" + Utility.regexStr(names[i]) + ")\\b",
            Pattern.CASE_INSENSITIVE).matcher(out);
      while (r.find()) {
        AnonymizedItem ai =
              new AnonymizedItem(out.substring(r.start(),r.end()), this.anonymizerType);
        ai.setStart(r.start());
        ai.setEnd(r.end());
        //log.debug("found:" + ai.getWord() + " at " + r.start() + "-" + r.end());
        list.add(ai);
      }
      try {
        out = r.replaceAll(replacements[i]);

      } catch (IllegalStateException | IllegalArgumentException
            | IndexOutOfBoundsException | NullPointerException e) {
        log.error(e.getMessage(),e);
      }

    }

    //process provided known name item discovered by NLP
    if (this.knownNameItems != null) {
      Set<String> uniqueWords = new HashSet();
      this.knownNameItems.forEach(i -> {
        list.add(i);
        uniqueWords.add(i.getWord());
      });

      for (String word: uniqueWords) {
        Matcher r = Pattern.compile("\\b(" + Utility.regexStr(word) + ")\\b",
              Pattern.CASE_INSENSITIVE).matcher(out);
        try {
          out = r.replaceAll(getLastNameSurrogate(word));
        } catch (SQLException e) {
          log.error(e.getMessage(),e);
        }
      }
    }
    //session.close();

    return out;
  }


  private void getNameSurrogate()  {
    if (names == null) {
      return;
    }
    replacements = new String[names.length];
    for (int i = 0; i < names.length; i++) {
      try {
        String repl =
            dic[i] != null && dic[i] == NameDictionay.Lastname ? getLastNameSurrogate(names[i])
              : getFirstNameSurrogate(names[i]);
        replacements[i] = repl;
      } catch (SQLException e) {
        log.error(e.getMessage(),e);
        replacements[i] = defaultReplacementWord;
      }
    }
  }

  static Map<String,Integer> lnCache = new HashMap<>();
  static Map<String,Integer> fnCache = new HashMap<>();

  int getFirstNameRange(String sex, int char1, int char2) throws SQLException {
    String key = "FN_" + sex + "_" + char1 + "_" + char2;
    if (fnCache.containsKey(key) && fnCache.get(key) != null) {
      return fnCache.get(key);
    }

    Integer range = inProcessDbBuilder.transactReturning(db -> {
      Integer result = db.get().toSelect(findFirstNameRangeBySexI12Query)
          .argString(sex)
          .argInteger(char1)
          .argInteger(char2)
          .queryOneOrNull(r -> r.getIntegerOrNull("range"));

      return result != null ? result : 0;
    });

    fnCache.put(key, range);

    return range;

  }


  int getLastNameRange(int char1, int char2) throws SQLException {
    String key = "LN_" + char1 + "_" + char2;
    if (lnCache.containsKey(key) && lnCache.get(key) != null) {
      return lnCache.get(key);
    }

    Integer range = inProcessDbBuilder.transactReturning(db -> {
      Integer result = db.get().toSelect(findLastNameRangeByI12Query)
          .argInteger(char1)
          .argInteger(char2)
          .queryOneOrNull(r -> r.getIntegerOrNull("range"));

      return result != null ? result : 0;
    });

    fnCache.put(key, range);
    return range;
  }

  String getLastNameSurrogate(String name) throws SQLException {

    org.javatuples.Pair<Integer,Integer> chars = Utility.getRandomChars(name);


    try {
      int range = getLastNameRange(chars.getValue0(),chars.getValue1());
      while (range < minLastNamePopularity) {
        chars = Utility.getRandomChars(name);
        range = getLastNameRange(chars.getValue0(),chars.getValue1());
      }

      final org.javatuples.Pair<Integer,Integer> charParam = chars;
      final Integer rangeParam = range;
      String surrogateName = inProcessDbBuilder.transactReturning(db -> {
        return db.get().toSelect(findLastNameByI12SeqQuery)
          .argInteger(charParam.getValue0())
          .argInteger(charParam.getValue1())
          .argInteger(Utility.getGaussianRandomPositionInRange(rangeParam, 10))
          .queryFirstOrNull(r -> r.getStringOrNull("name"));
      });
      return StringUtils.capitalize(surrogateName.toLowerCase());

    } catch (DatabaseException e) {
      log.error(e.getMessage(), e);
    }

    return defaultReplacementWord;

  }


  String getFirstNameSurrogate(String name) throws SQLException {
    String out;

    String sex = inProcessDbBuilder.transactReturning(db -> {
      String result = db.get().toSelect(searchNameQuery)
          .argString(name)
          .queryOneOrNull(r -> r.getStringOrNull("sex"));

      return result != null ? result : "F";
    });

    org.javatuples.Pair<Integer,Integer> chars = Utility.getRandomChars(name);
    int range = getFirstNameRange(sex, chars.getValue0(), chars.getValue1());
    while (range < minFirstNamePopularity) {
      chars = Utility.getRandomChars(name);
      range = getFirstNameRange(sex, chars.getValue0(), chars.getValue1());
    }

    final org.javatuples.Pair<Integer,Integer> charParam = chars;
    final Integer rangeParam = range;
    String surrogateFirstName = inProcessDbBuilder.transactReturning(db -> {
      return db.get().toSelect(findFirstNameBySexI12SeqQuery)
        .argString(sex)
        .argInteger(charParam.getValue0())
        .argInteger(charParam.getValue1())
        .argInteger(Utility.getGaussianRandomPositionInRange(rangeParam, 4))
        .queryFirstOrNull(r -> r.getStringOrNull("name"));
    });

    if (surrogateFirstName != null) {
      return StringUtils.capitalize(surrogateFirstName.toLowerCase());
    } else {
      log.warn(String.format("failed to find match for range:%s for [%s][%s] ",
          range, chars.getValue0(), chars.getValue1()));
      return defaultReplacementWord;
    }

  }


  public static final class Builder {

    private String[] names;
    private String anonymizerType;
    private String zipCode;
    private String gender;
    private Date dob;
    private NameDictionay[] dic;
    private List<AnonymizedItem> knownNameItems;

    public Builder() {
    }

    public Builder withNames(String[] names) {
      this.names = names;
      return this;
    }

    public Builder withAnonymizerType(String anonymizerType) {
      this.anonymizerType = anonymizerType;
      return this;
    }

    public Builder withZipCode(String zipCode) {
      this.zipCode = zipCode;
      return this;
    }

    public Builder withGender(String gender) {
      this.gender = gender;
      return this;
    }

    public Builder withDob(Date dob) {
      this.dob = dob;
      return this;
    }

    public Builder withDic(NameDictionay[] dic) {
      this.dic = dic;
      return this;
    }

    public Builder withKnownNameItems(List<AnonymizedItem> knownNameItems) {
      this.knownNameItems = knownNameItems;
      return this;
    }

    /**
     * builder.
     * @return new NameSurrogate object
     * @throws SQLException when in-memory database fails
     */
    public NameSurrogate build() throws SQLException {
      NameSurrogate nameSurrogate = new NameSurrogate(names, anonymizerType, null);
      nameSurrogate.zipCode = this.zipCode;
      nameSurrogate.gender = this.gender;
      nameSurrogate.dic = this.dic;
      nameSurrogate.dob = this.dob;
      nameSurrogate.knownNameItems = this.knownNameItems;
      if (nameSurrogate.knownNameItems == null) {
        nameSurrogate.knownNameItems = new ArrayList<>();
      }
      return nameSurrogate;
    }
  }
}