# 배포물과 권리 안내

이 프로젝트는 개인이 개발한 비공식 도구이며 ANBERNIC이 제작·승인·보증한 배포판이라는 뜻이 아닙니다.

공개 소스에는 직접 작성한 Under-clock Java 코드·프로필 자료·빌드/패치 도구와 Gradle Wrapper를 포함합니다. 이전 개발 저장소의 커밋 이력과 원시 기기 로그는 포함하지 않습니다. 소스 공개만으로 임의 재사용·변경·재배포 라이선스를 부여하는 것은 아니며, 이 배포에서는 별도 오픈소스 라이선스를 새로 지정하지 않았습니다.

배포 APK에는 com.anbernic.control1.0.29의 수정본과 복구용 순정본이 포함됩니다. 원래 Control Center 코드·리소스·자산·라이브러리의 권리는 각 권리자에게 있으며, 본 안내가 그 권리에 대한 별도 허락을 부여하지 않습니다. 제조사 재배포 조건/허가 확인은 별도 미완료 항목입니다. APK 공개를 제조사의 재배포 승인으로 해석하지 마세요.

Gradle Wrapper는 Gradle 프로젝트의 Apache License2.0 구성요소입니다. JUnit4.13.2(Eclipse Public License1.0)는 테스트 의존성이며 제품 APK에 포함되지 않습니다. Android Gradle Plugin·SDK·JDK·Apktool·androguard·서명 키는 소스 ZIP에 포함하지 않으며 각 공식 배포·라이선스 조건을 따릅니다.

참고 설계/플랫폼 자료:
- Gradle: https://github.com/gradle/gradle/blob/v8.13.0/LICENSE
- JUnit4: https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt
- Android 서명: https://source.android.com/docs/core/ota/sign_builds
- 초기 JSON 교환 형식 참고: https://github.com/AurelioB/ClusterTune

서명 키는 공개 저장소·APK·소스 ZIP에 넣지 않습니다. 서명은 제조사 펌웨어와의 호환성 조건이며 개발자 고유의 인증을 대신하지 않습니다.
